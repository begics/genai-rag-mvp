# FOR-SLaven: Document screening and human review

## What changed

New document indexing requests scan original uploaded bytes with ClamAV before extraction or transcription. Files with malware detections or scanner outages are blocked before any text/media is sent to AI. A file that passes the scanner proceeds to text extraction and model screening, then pauses for a person's approval. Only an explicit approval starts chunking, embedding, and saving the document in the RAG index. Rejection clears the extracted text and does not index it.

The model returns observations with exact quoted evidence, a source location, an indicator category, and uncertainty or missing information. It does not issue a fraud/not-fraud or safe/unsafe verdict. Neither model screening nor antivirus proves that a document is safe; the reviewer still needs to inspect it and verify important claims independently.

The expanded system map in the private UI now shows the order of the uploaded-file scan, text extraction/transcription, evidence screening, owner approval, and indexing. It identifies ClamAV as a private Compose component and shows the configured screening/planner model after sign-in; this is configuration information, not a live scanner-health check. The UI also clarifies that direct text submissions have no original uploaded file for ClamAV to scan.

## Provider and executor limits

The synchronous RAG query waits for each AI operation, so a slow remote provider can occupy a servlet request thread. The code now gives OpenAI-compatible Infomaniak chat/embedding calls an explicit request timeout and bounds rerank/transcription HTTP connect and read times separately. It also limits concurrent provider operations with a shared semaphore. The semaphore rejects immediately at capacity with HTTP 503, rather than letting an unbounded line of provider calls build up. Its scope is one application process, not a cluster-wide limit.

Micrometer records provider call duration/count, provider errors and rejections, current active calls and the configured capacity. The indexing executor has active-worker, queue-depth, remaining-capacity, and rejection metrics. It is intentionally bounded to two workers and a 100-task queue; if a new submission is rejected, the durable job is marked failed and the caller receives 503. This avoids telling clients a rejected job was accepted. Operators can read the exposed Actuator metrics endpoint only after authentication.

On application startup, persisted queued jobs are dispatched into the bounded executor. If the executor fills during recovery, the dispatcher stops without failing those older jobs, then periodically retries them as queued tasks finish. Each query fetches at most 100 jobs while excluding IDs already reserved for dispatch, so memory and repeated database work stay bounded by executor capacity rather than total backlog size. A synchronized in-process dispatch reservation prevents a concurrent API submission and recovery scheduler from both submitting the same job; it also ensures an API request waits for the current queue-admission attempt and sees its rejection instead of returning success prematurely. This keeps restart recovery compatible with a bounded queue instead of assuming every durable job fits in memory at once.

Whisper transcription now starts one overall deadline before the provider submission and carries it through polling and transcript download. Before each HTTP request, the remaining time is used as that request's maximum read timeout, and polling sleeps only for the lesser of the configured interval and remaining time. Thus a slow in-flight request cannot continue past the whole transcription budget by the full configured read timeout.

These are not a reactive rewrite and do not reduce provider latency. A query still waits for embedding, database retrieval, reranking, answer generation, and grounding verification; the changes bound resource usage and make failures/latency observable. If metrics later show request-thread pressure, add durable asynchronous query jobs with separate capacity, rather than simply making every remote call asynchronous and losing request/job ownership.

## How the flow works

1. The upload is stored in S3-compatible storage, and the app records who uploaded it and when.
2. The indexing job extracts text and persists it temporarily so the worker can recover without repeating extraction unnecessarily.
3. Before extraction, the S3 reader sends the original file bytes to the private ClamAV daemon using its INSTREAM protocol. It continues only for a clean scanner response; a detection or communication/protocol failure blocks the workflow.
4. The screening adapter sends extracted text through the existing provider PII redactor. It requests a bounded JSON result with at most eight evidence-based findings and no verdict or risk score.
5. Before storing a finding, the app checks that its evidence is an exact quotation from the text sent to the model and that its location matches the deterministic line/page position of the quote. The screening limit is 100,000 characters; invalid model output and offline screening fail closed.
6. The job enters `AWAITING_APPROVAL`. The owner can approve or reject it. Approval queues the job for indexing; rejection clears its text.
7. The job records submitter, upload, screen, reviewer, and indexing metadata for later review.

The existing controller → service → store/provider boundaries remain in place. The worker still owns the indexing lifecycle, while the store enforces state transitions and the workspace-scoped controller/service layer handles owner actions. Re-embedding jobs are separate and do not need to rescreen unchanged source text. Existing jobs are preserved by making review mandatory only for new ingestion jobs.

## Decisions and alternatives

- **Require approval for every document:** Auto-approval based on model output could turn uncertain classification into access. The chosen rule keeps the model advisory and makes approval explicit in every case.
- **No verdict, score, or categorical summary:** The model reports only observations and uncertainty, which reduces the chance that reviewers treat an uncertain classifier output as a fraud determination.
- **Require quoted evidence and verifiable locations:** Exact quotations make findings inspectable, and line/page locations are computed from the extracted input and checked against the model's location claim. Quotes are checked against the PII-redacted provider input, so masked contact details may appear as redaction markers.
- **Fail closed:** Offline mode cannot produce a screening result, and oversized text is not silently truncated. This prevents accidental unreviewed indexing, at the cost of making indexing unavailable until screening is configured.
- **Fail closed on antivirus outages and detections:** A scanner outage must not silently bypass the gate. The source remains in object storage for an administrator to inspect or retry after the scanner recovers; it is not extracted or sent to AI.

## Important limits and risks

- The model can overlook indicators or raise unsupported concerns. The reviewer must inspect the original source.
- ClamAV signatures can miss threats, and scanner health/signature freshness remain operational responsibilities. A successful scan is not proof of safety.
- Text and transcripts are sent to Infomaniak for screening. The text request uses PII masking, but the original audio/video sent to Whisper is not redacted.
- Scanned PDFs still need OCR and cannot be screened or indexed by the existing extraction flow.
- PDF extraction uses a temp-file-backed PDFBox cache capped at 128 MiB and rejects files over 200 pages or 100,000 extracted characters. This is not an OS-level parser sandbox and does not impose a hard CPU-time limit.
- Search query and filter values are bound parameters in PostgreSQL statements; integration coverage verifies SQL-injection-shaped filters do not escape workspace scope.
- Old indexed content is not retroactively screened.
- The database migration is local code only; it has not been applied to the VM. Do not deploy or migrate without explicit approval.
- Live evaluation scripts automatically approve only their synthetic test documents. They are test automation, not a bypass for normal UI/API use.

## Files and verification

The implementation is centered on `IndexingJobWorker`, `IndexingJobStore`, `IndexingJobService`, `S3DocumentReader`, the ClamAV scanner, the `DocumentRiskScreening` adapters, and migration V12. The browser review UI and its CSP hash were updated; the API exposes workspace-scoped approve/reject actions. The system map names the scanner and screening model alongside the visible review gate. Provider call limits, timeouts, Micrometer metrics, and queue-saturation handling are configured in `InfomaniakAiModels`, `ProviderCallLimiter`, and `IndexingConfiguration`. README and handoff documentation describe the gate and its limits.

Existing Java tests cover evidence and location validation, prompt-injection handling, PDF page/text bounds, SQL-injection-shaped filters, worker ordering, service transitions, workspace-scoped API access, PostgreSQL persistence, ClamAV protocol streaming, detection, and scanner outage behavior. Additional tests cover provider limits, timeout configuration, executor saturation, dispatch-race behavior, bounded backlog queries, and Whisper's remaining-deadline budget. The targeted Maven command could not start: the sandbox denied execution of the Maven binary under `~/.m2/wrapper/dists`; therefore these latest Java changes remain uncompiled and untested. `git diff --check` and YAML parsing pass. No provider call or deployment was performed.

## Transferable lessons

- Put a security gate in the durable job state machine, not only in the user interface. Otherwise a legacy API route or restart recovery path can skip the intended policy.
- Treat classifier output as untrusted input too: validate its schema, bounds, source position, and supporting evidence before showing or acting on it.
- Keep malware scanning in front of document parsing and model calls, and fail closed if the scanner cannot give a clean result.
- Keep model observations separate from authorization. Evidence and uncertainty can inform a person, but should not grant permission to index.
- When changing inline browser code under hash-based CSP, recalculate the hash and keep a test that verifies it against the actual HTML.
- ClamAV is memory intensive; size the VM for the scanner in addition to the application, database, and object store, and keep signature updates and scanner health monitored.
- A durable queue can outlive the capacity of an in-memory executor. Recovery should leave overflow work durable, retry it when capacity returns, and track submissions to avoid filling the executor with duplicate attempts.
- When a request and recovery scheduler can dispatch the same durable item concurrently, the reservation must cover both queue admission and rejection handling; otherwise a request can report acceptance even though its submission was rejected.
- A timeout checked only between polling requests is not an end-to-end deadline. Carry one deadline through submit, each bounded request, sleeps, and final download.

## Learning note: showing token generation inside RAG

The linked LinkedIn post explains that a language model processes a prompt, scores possible next tokens, chooses a token, and repeats until it has an answer. That is a useful model-internals lesson, but it is not the whole story for this application: a RAG system first checks workspace access and retrieves passages, then supplies a selection of those passages alongside the question. Once the model has drafted its answer, application logic checks grounding and returns source references. The distinction matters because a fluent model response and a relevant-looking citation do not independently prove a claim.

The demo and signed-in UI now use the same original, responsive six-step diagram: scope the question, retrieve context, prefill the prompt, decode tokens, check grounding outside the model, and return the answer with sources. It explains the prefill/decode split and the role of KV caching without pretending that this application streams visible tokens; the current UI receives the provider's completed response. The diagram is inserted into the existing architecture explanations rather than the chat composer, so it remains optional system detail and does not compete with the user's main task.

The design is intentionally an original HTML/CSS flow, not a downloaded or redrawn copy of the LinkedIn artwork. This avoids turning somebody else's graphic into an app asset while still teaching the underlying idea. The figure credits and links to the author's post; separating inspiration from copied expression is a practical way to respect creator attribution and keep the new visual specific to this application's retrieval, authorization, and validation stages.

One shared stylesheet supplies the same visual rules on the public demo and private workspace. Keeping the diagram in semantic HTML (an ordered list with meaningful headings and descriptions) lets it reflow into a compact two-column layout on phones, remain selectable text, and work with assistive technology. It also avoids adding a raster image, third-party image request, JavaScript rendering dependency, or a new inline script/style block. The signed-in page uses a self-hosted stylesheet permitted by the existing Content Security Policy, so the hash-authorized inline UI code does not need to be weakened or recalculated.

The test addition checks that the public demo can load the shared stylesheet and exposes the conceptual diagram and source attribution. The existing security test verifies inline CSP hashes against the actual signed-in HTML, which should continue passing because the change only adds markup outside the inline blocks. A useful next validation is to open both pages at desktop and mobile widths and confirm that the optional diagram wraps without horizontal scrolling; a source-level test alone cannot prove visual balance.

### Transferable lessons

- Explain systems at the boundary between components: retrieval is an application step, generation is a model step, and grounding/citation checks happen outside the model.
- Label conceptual visuals honestly. Do not imply that token-level internals are a live trace or that citations guarantee factual correctness.
- When adapting a linked graphic, preserve the idea in an original composition, credit the inspiration, and avoid reusing the underlying image without permission.
- A shared semantic structure is often more responsive and accessible than embedding a fixed-size infographic; keep the visual decoration separate from the meaning.
- Check Content Security Policy when adding UI assets. Self-hosted external CSS can be allowed narrowly without broadening inline-script permissions.

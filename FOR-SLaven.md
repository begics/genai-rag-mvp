# FOR-SLaven: Document screening and human review

## What changed

New document indexing requests scan original uploaded bytes with ClamAV before extraction or transcription. Files with malware detections or scanner outages are blocked before any text/media is sent to AI. A file that passes the scanner proceeds to text extraction and model screening, then pauses for a person's approval. Only an explicit approval starts chunking, embedding, and saving the document in the RAG index. Rejection clears the extracted text and does not index it.

The model returns observations with exact quoted evidence, a source location, an indicator category, and uncertainty or missing information. It does not issue a fraud/not-fraud or safe/unsafe verdict. Neither model screening nor antivirus proves that a document is safe; the reviewer still needs to inspect it and verify important claims independently.

The expanded system map in the private UI now shows the order of the uploaded-file scan, text extraction/transcription, evidence screening, owner approval, and indexing. It identifies ClamAV as a private Compose component and shows the configured screening/planner model after sign-in; this is configuration information, not a live scanner-health check. The UI also clarifies that direct text submissions have no original uploaded file for ClamAV to scan.

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

The implementation is centered on `IndexingJobWorker`, `IndexingJobStore`, `IndexingJobService`, `S3DocumentReader`, the ClamAV scanner, the `DocumentRiskScreening` adapters, and migration V12. The browser review UI and its CSP hash were updated; the API exposes workspace-scoped approve/reject actions. The system map names the scanner and screening model alongside the visible review gate. README and handoff documentation describe the gate and its limits.

Java tests cover evidence and location validation, prompt-injection handling, PDF page/text bounds, SQL-injection-shaped filters, worker ordering, service transitions, workspace-scoped API access, PostgreSQL persistence, ClamAV protocol streaming, detection, and scanner outage behavior. The complete `./mvnw -q test` suite passed, including PostgreSQL Testcontainers integration tests, and `git diff --check` passed. No provider call or deployment was performed.

## Transferable lessons

- Put a security gate in the durable job state machine, not only in the user interface. Otherwise a legacy API route or restart recovery path can skip the intended policy.
- Treat classifier output as untrusted input too: validate its schema, bounds, source position, and supporting evidence before showing or acting on it.
- Keep malware scanning in front of document parsing and model calls, and fail closed if the scanner cannot give a clean result.
- Keep model observations separate from authorization. Evidence and uncertainty can inform a person, but should not grant permission to index.
- When changing inline browser code under hash-based CSP, recalculate the hash and keep a test that verifies it against the actual HTML.
- ClamAV is memory intensive; size the VM for the scanner in addition to the application, database, and object store, and keep signature updates and scanner health monitored.

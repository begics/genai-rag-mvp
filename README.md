# GenAI RAG MVP

This is a single Spring Boot 4.2 application with PostgreSQL/pgvector. It ingests plain text and PDFs from RustFS, retrieves stored chunks with hybrid PostgreSQL full-text and pgvector search, and returns answers with source references. Infomaniak AI Services provides the chat and embedding models through its OpenAI-compatible API.

## IntelliJ and local startup

Copy `.env.example` to `.env` and replace the placeholder credentials and AI service settings. Run `docker compose up -d` to start the application, Keycloak, PostgreSQL, and RustFS. Open `http://localhost:8080/`; the app redirects to Keycloak at `http://localhost:8180` for sign-in. Keycloak self-registration is enabled for local development. Compose seeds a `ROLE_ADMIN` account in the `rag` realm using `KEYCLOAK_ADMIN_USERNAME` (default `admin`), `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_EMAIL`, `KEYCLOAK_ADMIN_FIRST_NAME`, and `KEYCLOAK_ADMIN_LAST_NAME`; use the configured username and password to sign in to the app and Keycloak's admin console. The realm admin account is created on first startup and is not reset when its password is changed later. Do not expose the development Keycloak port publicly.

Set your Infomaniak AI Services API token, product ID, available chat model ID, embedding model, and reranker in `.env`. The selected BGE Multilingual Gemma2 embedding model uses 3,584 dimensions. Chunks store these embeddings as `halfvec(3584)` with the model ID recorded per chunk; retrieval excludes vectors produced by other models. Search combines PostgreSQL full-text and vector results, then reranks up to 20 candidates with the Cohere-compatible reranking endpoint. Infomaniak documents `GET /1/ai` to find the product ID and `GET /2/ai/{product_id}/openai/v1/models` to list chat and embedding models, plus `/2/ai/{product_id}/cohere/v1/models` to list rerankers.

The local model provider defaults to `offline` when no `.env` is loaded, so automated tests need no credentials or provider calls. Its hash-based embeddings, pass-through reranker, and canned chat response are a smoke-test fallback, not semantic retrieval. With the example `.env` configured, `RAG_PROVIDER=infomaniak` selects Infomaniak for chat, embeddings, and reranking.

After signing in, each user receives a private personal workspace. Create additional private workspaces in the UI; workspaces are accessible only to their owner. Indexed documents are listed persistently for the selected workspace, including each document’s indexed chunk count. All document retrieval, embedding reindexing, uploads, and job status lookups are scoped to the selected workspace. The UI handles browser session security and CSRF automatically. API clients can authenticate with Keycloak-issued Bearer access tokens and select a workspace with `X-Workspace-ID`; workspace ownership is checked on every request.

The API accepts OIDC login sessions for the browser and JWT Bearer tokens for API clients; Basic authentication is not enabled. Keycloak maps realm roles to Spring authorities (`USER` and `ADMIN`), while PostgreSQL workspace membership remains the authorization source for tenant data. Admins use their personal workspace by default, like other users. Security protections include a same-site CSRF cookie/token for browser POST requests, a restrictive Content Security Policy, anti-framing and MIME-sniffing headers, and rate limits. Uploads are checked against file signatures or strict UTF-8 before being written to object storage; the supplied MIME type is not trusted. The in-memory rate limiter is intended for this single-instance deployment. Compose ports bind to loopback; before exposing the service, configure TLS at a trusted reverse proxy, restrict proxy access to the app, and configure Keycloak's hostname, redirect URIs, and secrets for that deployment.

For non-browser API use, obtain a Keycloak access token through an OAuth2 Authorization Code + PKCE flow (direct password grants are disabled), and set `ACCESS_TOKEN` and `WORKSPACE_ID` to the token and an assigned workspace UUID. Uploads must use a workspace-prefixed S3 key such as `uploads/$WORKSPACE_ID/notes/notes.pdf`. Then enqueue background indexing with `POST /api/indexing-jobs/s3` (the bucket comes from `S3_BUCKET`):

```json
{"key":"uploads/$WORKSPACE_ID/notes/notes.pdf"}
```

The endpoint returns `202 Accepted` and a job ID. Check progress with `GET /api/indexing-jobs/{id}`; completed jobs include the indexed chunk count, while failed jobs expose an error message. Jobs are persisted in PostgreSQL, resumed after an app restart, and use the job ID as an idempotency key so recovery does not duplicate documents. The worker accepts UTF-8 text and PDFs with extractable text; scanned/image-only PDFs require OCR and are not supported.

The UI shows the indexing pipeline from the browser and RustFS S3 storage through the PostgreSQL job queue, background workers, text chunking, the configured embedding service, and PostgreSQL pgvector storage. It polls recent workspace jobs every three seconds while work is active and shows each job's persisted stage and final result.

Alternatively, enqueue text directly with `POST /api/indexing-jobs`:

```json
{"source":"notes.txt","text":"PostgreSQL stores relational data and supports extensions."}
```

For API requests, send the bearer token and workspace header. Browser clients using the OIDC session instead must preserve the `XSRF-TOKEN` cookie and send its value in `X-XSRF-TOKEN` for state-changing requests; the built-in UI does this automatically.

```sh
curl -H "Authorization: Bearer $ACCESS_TOKEN" -H "X-Workspace-ID: $WORKSPACE_ID" \
  -H 'Content-Type: application/json' \
  -d '{"source":"notes.txt","text":"PostgreSQL stores relational data and supports extensions."}' \
  http://localhost:8080/api/indexing-jobs
```

Text is split at paragraph boundaries with a 1,500-character ceiling and a 200-character overlap. Very long paragraphs split at word boundaries where possible. Embeddings are sent in batches of at most 100 chunks per provider request. This character-based limit is a tokenizer-independent approximation, not a model token limit.

Query with `POST /api/query`: `{"question":"What stores relational data?"}`. PostgreSQL full-text search (`simple` configuration and GIN index) retrieves exact terms, while pgvector retrieves semantically similar chunks; reciprocal rank fusion combines their rankings, then Infomaniak reranks the candidates before the chat model receives context. To generate embeddings for chunks whose recorded model differs from the active model, call the authenticated endpoint:

```sh
curl -X POST -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Workspace-ID: $WORKSPACE_ID" \
  http://localhost:8080/api/documents/reindex-embeddings
```

The response contains a job ID; poll `GET /api/indexing-jobs/{id}` with the same Bearer and workspace headers until `COMPLETED` or `FAILED`. Reindex jobs only process chunks in the selected workspace with missing or stale model versions and are safe to retry. Requests are validated; blank and oversized input is rejected.

Run tests with `./mvnw test`. ArchUnit enforces package dependency boundaries and cycle-free slices. PostgreSQL integration tests use a disposable Testcontainer and are skipped when Docker is unavailable. The small deterministic retrieval evaluation corpus is in `src/test/resources/retrieval-evaluation.json`; its integration test checks Recall@5 ≥ 1.0 and MRR ≥ 0.75. It exercises lexical and vector retrieval only, not live model answer quality.

For a live end-to-end evaluation against the local Infomaniak-backed app, run `npm ci` once and then `npm run eval:rag:live`. The runner requires the local Compose services to be running and `RAG_PROVIDER=infomaniak` with the Keycloak and Infomaniak settings configured in `.env`. Promptfoo tests two synthetic facts for answer and source attribution, plus a question that must not be answerable from the test corpus. The runner creates a temporary workspace and removes its jobs and documents afterward, including when evaluation fails. Live model calls may incur provider usage; no project documents are sent.

To also evaluate against a small, selected HSLU repository sample, run `HSLU_REPO_PATH=/path/to/cas-llms-and-ai-agents-hs-2026 npm run eval:rag:hslu`. This separate live evaluation reads only the tracked `wiki/rag-course-architecture.md` file from that checkout; it does not ingest the whole repository, PDFs, notebooks, local/untracked files, or credentials. The file is sent to the configured Infomaniak services for indexing and answer generation. Confirm that this use is permitted for the course material before running it. Results are not shared or written to Promptfoo's local result store, and the temporary workspace is removed afterward.

GitHub Actions runs the Maven test suite, validates the Compose configuration,
and builds the application image on pushes and pull requests. CI uses disposable
offline test credentials, not deployment secrets. Copilot path-specific rules
are in `.github/instructions/`; reusable task prompts are in `.github/prompts/`.

## Kubernetes preparation

Review `deploy/k8s/` and customize the image, storage class, resource limits, product/model IDs, embedding dimension, external S3 endpoint, Keycloak issuer/endpoints, client redirect URI, and secret values for the target operator. Configure a reachable Keycloak realm; the sample manifests do not deploy an identity provider. PostgreSQL is a ClusterIP service with an explicit PVC and health probes. `secret.example.yaml` contains placeholders only; replace them before applying any resources. The Dockerfile builds the application image with JDK 27. These manifests are preparation artifacts: this repository never applies them or accesses a cluster.

Local RustFS stores original files; the application reads UTF-8 text and extracts text from PDFs, then stores chunks and embeddings in PostgreSQL. OCR for scanned PDFs, other binary formats, workers, ingress/DNS/TLS and backup automation are intentionally deferred from this MVP. Configure TLS at the ingress or reverse proxy before exposing the API beyond a trusted private network.

The Compose stack also includes Portainer CE at `https://localhost:9443` for Docker management. Its port is bound to loopback; connect to the VM through SSH port forwarding rather than exposing the UI publicly. Portainer has access to the Docker socket and therefore effectively has host-level control.

For the public VM deployment, the optional `public` Compose profile starts Traefik on ports 80 and 443. It routes `ai-rag.begic.ch` to the app and `auth.begic.ch` to Keycloak, and obtains certificates through ACME HTTP validation. Start it with `docker compose --profile public up -d`. Set `KEYCLOAK_PUBLIC_URL=https://auth.begic.ch` in the deployment `.env`, allow inbound TCP 80/443 in the cloud firewall, and ensure the Cloudflare DNS records point to the VM. Once the origin certificate has been issued, set Cloudflare SSL/TLS mode to Full (strict). Do not expose PostgreSQL, RustFS, or Portainer. The realm import allows the HTTPS app callback while retaining the localhost callback for local development. The ACME certificate data is persisted in the `traefik-acme` volume.

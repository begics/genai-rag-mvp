# GenAI RAG MVP Handoff

## Current state

- IntelliJ-ready Maven project at this repository root: Java 27, Spring Boot 4.2.0, Spring AI 2.1.0, PostgreSQL/pgvector.
- Infomaniak AI Services chat and embeddings use the OpenAI-compatible API. Configuration is in `.env.example` and `deploy/k8s/`; account-specific API token, product ID, model IDs and embedding dimension must be supplied locally.
- The Compose stack is deployed on the Infomaniak VM using PostgreSQL/pgvector, RustFS, and the Spring Boot app. The database, API, S3 API, and RustFS console bind to loopback only; use an SSH tunnel to access them. Bucket initialization is automatic.
- Portainer CE 2.45.2 is included in Compose for container management, published on loopback port 9443 with persistent state. Its first-run administrator account must be created promptly after launch.
- The deployed `.env` selects `RAG_PROVIDER=offline` because no Infomaniak AI credentials were configured. The generated Compose credentials are in the ignored local `.env` (mode 600) and the remote `/home/ubuntu/genai-rag/.env` (mode 600); do not commit or print them.
- Keycloak/OIDC and workspace isolation are implemented locally but have not yet been deployed to the existing VM. Compose now includes Keycloak; V7 backfills existing documents and indexing jobs into the legacy workspace. Browser login, Bearer JWT API access, private personal workspaces, member sharing, and workspace-scoped retrieval/indexing are covered by tests. Do not apply V7 to the deployed database until an explicit migration/deployment is approved.
- The document review gate is implemented locally in V12 but has not been applied to the VM. New indexing requests, including the legacy `POST /api/documents/s3` route, now scan uploaded bytes with the private Compose ClamAV service before extraction, then return exact quoted evidence, validated line/page locations, indicator categories, and uncertainty/missing context; no fraud/safety verdict is returned. Workspace-owner approval remains required before indexing. The UI system map shows the ClamAV scan, extraction/transcription, evidence-screening model, and approval gate. Malware detections, scanner outages, and offline AI screening fail closed. Do not apply V12 or deploy without explicit approval.
- Provider-operation hardening is implemented locally: Infomaniak calls have configurable request/connect/read timeouts, one default retry, a shared fail-fast concurrency limit, and Micrometer latency/error/rejection metrics. The bounded indexing executor reports active/queued/capacity metrics; rejected enqueue requests are marked failed and return 503. Persisted queued jobs rejected during startup recovery remain queued and are retried periodically as capacity becomes available. `/actuator/metrics` is exposed but remains subject to authentication. No deployment was performed.
- `POST /api/documents/s3` reads UTF-8 text or extracts text from PDFs in the configured bucket, then places extracted text in the review workflow; approved content is indexed into PostgreSQL and original files stay in RustFS. OCR for image-only PDFs is not supported.
- `RAG_EMBEDDING_DIMENSIONS` drives the Flyway vector column and both online/offline embeddings. It must match the embedding model output and be at most 2000 for the HNSW index.
- The previous baseline passed `./mvnw test` with Java 27, including pgvector integration and S3 ingestion coverage. The uncommitted review-gate change has not yet been compiled or tested because the sandbox blocked execution of the Maven wrapper. Compose YAML parsed successfully; the deployed API and storage health checks pass.
- No live AI provider call, Kubernetes deployment, commit or push was performed. The existing `.gitignore` is preserved; `pck-odnd2cl-kubeconfig` remains ignored and must not be read.
- Local deployment verification (2026-11-25): the Compose app and Keycloak are running on loopback; V7 is applied to the local database. 69 Maven tests passed, and live OIDC login, admin-role mapping, workspace access, CSRF, workspace isolation, and logout passed. No Infomaniak inference was triggered. The existing VM was not changed.

## Open work

1. Configure Infomaniak product ID, API token, available chat/embedding model IDs and matching vector dimension in a local `.env`; do not commit secrets.
2. Before deploying Keycloak, choose public issuer/redirect URLs, configure TLS and a trusted reverse proxy, and replace local bootstrap credentials. Compose currently provides development-mode Keycloak only.
3. Configure backups for the PostgreSQL and RustFS volumes.
4. Kubernetes manifests remain preparation artifacts only; the live deployment uses Docker Compose.
5. Commit or push only when explicitly requested.

## Resume

Open this project in IntelliJ, then start a new Copilot Chat session and ask it to read this `HANDOFF.md` and `README.md`. The active Copilot conversation is not automatically transferred into a different IDE window.

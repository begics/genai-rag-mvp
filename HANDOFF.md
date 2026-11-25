# GenAI RAG MVP Handoff

## Current state

- IntelliJ-ready Maven project at this repository root: Java 27, Spring Boot 4.2.0, Spring AI 2.1.0, PostgreSQL/pgvector.
- Infomaniak AI Services chat and embeddings use the OpenAI-compatible API. Configuration is in `.env.example` and `deploy/k8s/`; account-specific API token, product ID, model IDs and embedding dimension must be supplied locally.
- The Compose stack is deployed on the Infomaniak VM using PostgreSQL/pgvector, RustFS, and the Spring Boot app. The database, API, S3 API, and RustFS console bind to loopback only; use an SSH tunnel to access them. Bucket initialization is automatic.
- Portainer CE 2.45.2 is included in Compose for container management, published on loopback port 9443 with persistent state. Its first-run administrator account must be created promptly after launch.
- The deployed `.env` selects `RAG_PROVIDER=offline` because no Infomaniak AI credentials were configured. The generated Compose credentials are in the ignored local `.env` (mode 600) and the remote `/home/ubuntu/genai-rag/.env` (mode 600); do not commit or print them.
- Keycloak/OIDC and workspace isolation are implemented locally but have not yet been deployed to the existing VM. Compose now includes Keycloak; V7 backfills existing documents and indexing jobs into the legacy workspace. Browser login, Bearer JWT API access, private personal workspaces, member sharing, and workspace-scoped retrieval/indexing are covered by tests. Do not apply V7 to the deployed database until an explicit migration/deployment is approved.
- `POST /api/documents/s3` reads UTF-8 text or extracts text from PDFs in the configured bucket and indexes chunks/embeddings in PostgreSQL; original files stay in RustFS. OCR for image-only PDFs is not supported.
- `RAG_EMBEDDING_DIMENSIONS` drives the Flyway vector column and both online/offline embeddings. It must match the embedding model output and be at most 2000 for the HNSW index.
- `./mvnw test` passes with Java 27, including pgvector integration and S3 ingestion coverage. Compose YAML parsed successfully; the deployed API and storage health checks pass.
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

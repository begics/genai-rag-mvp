# GenAI RAG MVP

This is a single Spring Boot 4.2 application with PostgreSQL/pgvector. It ingests plain text and PDFs from RustFS, retrieves stored chunks with hybrid PostgreSQL full-text and pgvector search, and returns answers with source references. Infomaniak AI Services provides the chat and embedding models through its OpenAI-compatible API.

## IntelliJ and local startup

Import `pom.xml` as a Maven project, select JDK 27, and run `GenaiRagApplication`. Copy `.env.example` to `.env`, replace the placeholder passwords, then run `docker compose up -d postgres rustfs s3-init` for local PostgreSQL and S3-compatible storage. The S3 API is available at `http://localhost:9000` and the RustFS console at `http://localhost:9001`; the bucket defaults to `rag-documents`. The Maven run uses `localhost`; the Compose app uses the internal `postgres` service name.

Copy `.env.example` to `.env` and set a unique, long `APP_PASSWORD` as well as your Infomaniak AI Services API token, product ID, available chat model ID, embedding model, and reranker. The selected BGE Multilingual Gemma2 embedding model uses 3,584 dimensions. Chunks store these embeddings as `halfvec(3584)` with the model ID recorded per chunk; retrieval excludes vectors produced by other models. The authenticated `POST /api/documents/reindex-embeddings` endpoint enqueues a background job to refresh embeddings from an older model in batches. Poll `GET /api/indexing-jobs/{id}` for completion; failed jobs report an error and can be retried by enqueuing another reindex job. Search combines PostgreSQL full-text and vector results, then reranks up to 20 candidates with the Cohere-compatible reranking endpoint; `BAAI/bge-reranker-v2-m3` is selected for multilingual content. The API requires HTTP Basic authentication using `APP_USERNAME` and `APP_PASSWORD`; only actuator health endpoints are public for probes. Use HTTPS/TLS whenever requests leave localhost or a trusted private network. Infomaniak documents `GET /1/ai` to find the product ID and `GET /2/ai/{product_id}/openai/v1/models` to list chat and embedding models, plus `/2/ai/{product_id}/cohere/v1/models` to list rerankers.

The local model provider defaults to `offline` when no `.env` is loaded, so automated tests need no credentials or provider calls. Its hash-based embeddings, pass-through reranker, and canned chat response are a smoke-test fallback, not semantic retrieval. With the example `.env` configured, `RAG_PROVIDER=infomaniak` selects Infomaniak for chat, embeddings, and reranking.

Open the English-language chat and upload UI at `http://localhost:8080/` (or the same origin as the deployed app); authenticate with the configured HTTP Basic credentials. It uploads TXT, Markdown, CSV, PDF, video, and audio files up to 100 MiB to the configured S3-compatible bucket (RustFS/MinIO). Use **Index for chat** on text and PDF uploads to add them to pgvector. Audio and video are stored as source files only: transcription and media indexing are not implemented. The current text/PDF ingestion limit is 20 MiB.

Upload a UTF-8 plain-text or PDF file using the AWS CLI (with local AWS credentials configured for RustFS), for example `aws --endpoint-url http://localhost:9000 s3 cp notes.pdf s3://rag-documents/notes.pdf`. Then enqueue background indexing with `POST /api/indexing-jobs/s3` (the bucket comes from `S3_BUCKET`):

```json
{"key":"notes.txt"}
```

The endpoint returns `202 Accepted` and a job ID. Check progress with `GET /api/indexing-jobs/{id}`; completed jobs include the indexed chunk count, while failed jobs expose an error message. Jobs are persisted in PostgreSQL, resumed after an app restart, and use the job ID as an idempotency key so recovery does not duplicate documents. Text and S3 jobs share a bounded background worker pool. The worker reads up to 20 MiB from RustFS, extracts embedded PDF text, then chunks, batch-embeds, and indexes it in PostgreSQL/pgvector. It accepts UTF-8 text and PDFs with extractable text; scanned/image-only PDFs require OCR and are not supported. Missing objects, malformed PDFs, non-UTF-8 text, blank documents, and oversized files are rejected. The endpoint requires the same HTTP Basic credentials as the other API routes.

```sh
curl -u "$APP_USERNAME:$APP_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"key":"notes.txt"}' http://localhost:8080/api/indexing-jobs/s3
```

Alternatively, enqueue text directly with `POST /api/indexing-jobs`:

```json
{"source":"notes.txt","text":"PostgreSQL stores relational data and supports extensions."}
```

Text is split at paragraph boundaries with a 1,500-character ceiling and a 200-character overlap. Very long paragraphs split at word boundaries where possible. Embeddings are sent in batches of at most 100 chunks per provider request. This character-based limit is a tokenizer-independent approximation, not a model token limit.

Query with `POST /api/query`: `{"question":"What stores relational data?"}`. PostgreSQL full-text search (`simple` configuration and GIN index) retrieves exact terms, while pgvector retrieves semantically similar chunks; reciprocal rank fusion combines their rankings, then Infomaniak reranks the candidates before the chat model receives context. To generate embeddings for chunks whose recorded model differs from the active model, call the authenticated endpoint:

```sh
curl -u "$APP_USERNAME:$APP_PASSWORD" -X POST \
  http://localhost:8080/api/documents/reindex-embeddings
```

The response contains a job ID; poll its `GET /api/indexing-jobs/{id}` endpoint until `COMPLETED` or `FAILED`. Reindex jobs only process chunks with missing or stale model versions and are safe to retry. Requests are validated; blank and oversized input is rejected.

Run tests with `./mvnw test`. ArchUnit enforces package dependency boundaries and cycle-free slices. PostgreSQL integration tests use a disposable Testcontainer and are skipped when Docker is unavailable. The small deterministic retrieval evaluation corpus is in `src/test/resources/retrieval-evaluation.json`; its integration test checks Recall@5 ≥ 1.0 and MRR ≥ 0.75. It exercises lexical and vector retrieval only, not live model answer quality.

GitHub Actions runs the Maven test suite, validates the Compose configuration,
and builds the application image on pushes and pull requests. CI uses disposable
offline test credentials, not deployment secrets. Copilot path-specific rules
are in `.github/instructions/`; reusable task prompts are in `.github/prompts/`.

## Kubernetes preparation

Review `deploy/k8s/` and customize the image, storage class, resource limits, product/model IDs, embedding dimension, external S3 endpoint and secret values for the target operator. PostgreSQL is a ClusterIP service with an explicit PVC and health probes. `secret.example.yaml` contains placeholders only; replace them before applying any resources. The Dockerfile builds the application image with JDK 27. These manifests are preparation artifacts: this repository never applies them or accesses a cluster.

Local RustFS stores original files; the application reads UTF-8 text and extracts text from PDFs, then stores chunks and embeddings in PostgreSQL. OCR for scanned PDFs, other binary formats, workers, ingress/DNS/TLS and backup automation are intentionally deferred from this MVP. Configure TLS at the ingress or reverse proxy before exposing the API beyond a trusted private network.

The Compose stack also includes Portainer CE at `https://localhost:9443` for Docker management. Its port is bound to loopback; connect to the VM through SSH port forwarding rather than exposing the UI publicly. Portainer has access to the Docker socket and therefore effectively has host-level control.

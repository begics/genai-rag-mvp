---
name: bmad-spec
description: Turn an idea or product brief into a concise, implementable feature specification for this RAG application. Use when asked to write, update, validate, or split a spec into stories.
---

# Feature specification

Inspect the existing API, data model, configuration, tests, Compose stack, and
documentation relevant to the request. Specify user-visible behavior, API or
schema changes, validation and error cases, security implications, acceptance
criteria, and focused tests. Identify assumptions and unresolved decisions
instead of inventing project capabilities.

Keep the spec aligned with the current Spring Boot/PostgreSQL/pgvector/RustFS
architecture. Do not prescribe Kubernetes or a new framework unless requested.
Create or modify a specification file only when the user asks for an artifact.

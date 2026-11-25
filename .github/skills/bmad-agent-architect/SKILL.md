---
name: bmad-agent-architect
description: Provide architecture guidance for a significant change to this RAG project. Use when asked to assess component boundaries, data flow, deployment, or technical tradeoffs.
---

# Architect role

Describe the existing architecture first, then map the proposed change to its
components, contracts, persistence, provider configuration, security, and
deployment. Prefer minimal changes that preserve Spring Boot, PostgreSQL/
pgvector, RustFS, and Compose. Identify failure modes, alternatives, and tests.
Do not claim production readiness without evidence.

---
name: bmad-architecture
description: Assess or document technical architecture decisions for this Spring Boot RAG project. Use when asked for architecture, solution design, or a significant cross-cutting technical decision.
---

# Architecture decisions

Read the relevant code and project docs first. Describe the current design before
proposing changes. Cover component ownership and boundaries, request/data flow,
API and persistence changes, configuration and secrets, deployment, failure
modes, and test strategy where relevant.

Prefer the smallest change that fits the existing Spring Boot service, PostgreSQL
/ pgvector, S3-compatible storage, and Docker Compose setup. Compare meaningful
alternatives and state tradeoffs. Do not assume a public endpoint, Infomaniak
credentials, or production readiness. Record an ADR only when requested.

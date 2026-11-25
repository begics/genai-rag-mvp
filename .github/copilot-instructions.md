# Copilot instructions

This repository is a Java 27 / Spring Boot 4.2 RAG MVP. It uses PostgreSQL with
pgvector, RustFS through an S3-compatible API, Docker Compose, Keycloak OIDC
login, workspace-scoped authorization, and an optional Infomaniak provider. Read
`README.md`, `HANDOFF.md`, and the affected source/tests before changing behavior.

- Preserve the existing controller → service → store/provider boundaries.
- Keep secrets out of source, logs, command output, and committed files. `.env`
  contains deployment credentials and must remain ignored.
- Compose-published services must stay bound to loopback unless the user
  explicitly requests a public deployment and its security configuration.
- Keep the offline provider usable without Infomaniak credentials.
- Add or update tests using the existing JUnit 5, Spring test, Mockito, and
  Testcontainers conventions. Run the smallest relevant test command.
- Do not deploy, change VM/firewall settings, or commit unless explicitly asked.

Use the repository skills in `.github/skills/` for the matching planning,
architecture, UX, implementation, testing, or review task. They are adapted to
this codebase and do not require the separate `_bmad` framework. Skills are
task-specific: select the relevant ones rather than running every workflow on
every change.

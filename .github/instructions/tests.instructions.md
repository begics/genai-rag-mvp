---
description: Project conventions for automated tests
applyTo: "src/test/java/**/*.java"
---

- Follow the repository's JUnit 5, Spring test, Mockito, and Testcontainers
  patterns.
- Keep unit tests deterministic and avoid live Infomaniak requests.
- Use Testcontainers for PostgreSQL/pgvector integration behavior when needed.
- Cover success paths plus relevant validation, security, and failure cases.
- Run the smallest relevant test selector, then the full `./mvnw test` suite
  when the change warrants it.

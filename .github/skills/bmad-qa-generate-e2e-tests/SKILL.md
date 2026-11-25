---
name: bmad-qa-generate-e2e-tests
description: Add or improve automated tests for an implemented API or user workflow. Use when asked to generate tests or add test coverage.
---

# Test design

Inspect existing test coverage and follow the project's JUnit 5, Spring test,
Mockito, and Testcontainers conventions. Cover the requested behavior, the
important validation/error paths, and security where applicable. Keep tests
deterministic and avoid live Infomaniak calls.

Use the smallest test layer that verifies the behavior. Do not add browser-test
frameworks or test dependencies unless the user requested end-to-end UI tests
or the existing stack cannot verify the requirement. Run the relevant tests and
report failures plainly; change production code only to fix a demonstrated bug.

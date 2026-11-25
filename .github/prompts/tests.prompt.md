---
name: tests
description: Add focused tests for the selected feature or class
agent: agent
argument-hint: Select a class or describe the behavior to test
---
Use the `bmad-qa-generate-e2e-tests` skill. Read the selected implementation and
existing tests first. Add focused JUnit 5 / Spring / Mockito / Testcontainers
tests following local patterns. Cover success and important invalid-input or
failure behavior; do not make live provider calls. Run the smallest relevant
test command and report the result.

---
name: bmad-build
description: Implement a feature, story, or bug fix in this RAG repository when the user asks for the change. Make the smallest complete change and verify it.
---

# Implement and verify

Read repository instructions, docs, affected source, and existing tests before
editing. Follow current naming, layering, dependency, and configuration patterns.
Implement the complete requested behavior; update directly related docs and tests.

Prefer typed, explicit validation and errors. Do not add dependencies or
abstractions without need. Never print secrets or commit credentials. Keep
network-facing Compose bindings on loopback unless public exposure is explicitly
requested and secured.

Run the smallest relevant existing test/build command and report its result.
Do not deploy to the VM, change firewall settings, or commit unless explicitly
requested.

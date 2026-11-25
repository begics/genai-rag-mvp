---
name: bmad-build-auto
description: Implement a clearly scoped and unambiguous feature end-to-end with minimal back-and-forth. Use when the user explicitly asks for an automatic or fast implementation.
---

# Automatic implementation

Use the `bmad-build` workflow. Resolve only non-blocking details from repository
conventions and document the assumptions. If a decision affects security,
credential exposure, data loss, public networking, or a significant product
tradeoff, stop and ask. Test the behavior and report exactly what was changed.
Never deploy, alter firewall rules, or commit without explicit permission.

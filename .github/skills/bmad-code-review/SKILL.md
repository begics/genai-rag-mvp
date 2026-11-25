---
name: bmad-code-review
description: Review an explicitly selected diff, pull request, or artifact for correctness, security, architecture, and test gaps. Use only when the user asks for a review.
---

# Code review

Review only the requested changes and their relevant context. Prioritize concrete
correctness, data-loss, security, deployment, and compatibility problems. Check
that secrets remain protected, API behavior is validated, Compose services stay
loopback-bound, and behavior changes have tests.

Report actionable findings with file and line, impact, evidence, and a concise
fix. Separate confirmed defects from suggestions. Do not edit the reviewed
changes unless the user asks for fixes.

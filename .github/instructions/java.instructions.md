---
description: Project conventions for Java production source
applyTo: "src/main/java/**/*.java"
---

- Keep the existing controller → service → store/provider boundaries.
- Use constructor injection, focused types, and explicit validation/errors.
- Preserve offline mode and avoid provider/network calls in unit tests.
- Keep credentials and sensitive configuration out of code and logs.
- Add or update tests for behavior changes; do not broaden the change without
  need.

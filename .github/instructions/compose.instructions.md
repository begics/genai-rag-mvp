---
description: Project conventions for Docker Compose and container deployment
applyTo: "compose.yaml,Dockerfile,.dockerignore"
---

- Bind host-published service ports to `127.0.0.1` by default.
- Do not add credentials to Compose files; consume environment variables and
  keep `.env` ignored.
- Preserve persistent volumes and service health/dependency ordering.
- Pin infrastructure images to explicit versions when practical.
- Validate changes with `docker compose config --quiet`; do not deploy without
  explicit user authorization.

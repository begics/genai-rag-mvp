---
name: deploy
description: Inspect or prepare a safe Docker Compose deployment for this RAG project
agent: ask
argument-hint: Describe the deployment change or environment to inspect
---
Use the `bmad-architecture` and `bmad-build` skills as appropriate. Inspect the
Compose configuration, Dockerfile, environment-variable requirements, service
health/dependencies, persistent volumes, and current documentation. Check that
published ports are loopback-bound and secrets are not in source or logs. Validate
with `docker compose config --quiet` and build/test where relevant.

Do not connect to a VM, deploy, restart services, modify firewall rules, expose
ports publicly, or print/read out credentials unless the user explicitly asks
for that action. State when TLS, backups, model credentials, or firewall
configuration are still missing.

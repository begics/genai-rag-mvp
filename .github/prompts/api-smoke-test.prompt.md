---
name: api-smoke-test
description: Run a safe smoke test of the RAG API and its important workflows
agent: agent
argument-hint: Specify a local or explicitly authorized test environment
---
Inspect the API contract and security configuration before sending requests.
Cover health, an unauthenticated protected request, authenticated text
indexing, and an authenticated query; include S3 ingestion only if the
configured bucket has a disposable test object. Verify response status and
shape, including source references. Use synthetic test content, do not print
credentials, and do not send requests to a remote VM unless the user explicitly
authorizes that target. Clean up only test data that the test created and can
identify safely. Report environment, checks, and failures.

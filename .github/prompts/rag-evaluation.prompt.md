---
name: rag-evaluation
description: Evaluate retrieval and answer quality with a small, reproducible RAG test set
agent: ask
argument-hint: Describe a corpus or RAG behavior to evaluate
---
Use the `bmad-qa-generate-e2e-tests` skill where code tests are needed. First
inspect the current chunking, embedding, retrieval, prompt, and source-reference
behavior. Propose a compact test set containing questions answerable from known
documents, questions with no supporting evidence, and relevant edge cases.
For each case record expected source/document, expected answer facts, and what
counts as an unsupported answer. Separate retrieval quality from answer quality;
do not treat fluent output or citations alone as proof of correctness. Keep
evaluation offline/deterministic unless the user explicitly requests a live
provider evaluation. Do not send project documents to third-party services
without permission. Report assumptions and reproducible commands.

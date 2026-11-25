---
name: bmad-ux
description: Define the interaction and visual design for a user-facing feature in this RAG application. Use when asked to plan a chatbot, document-management, or other UI experience.
---

# User experience

Understand the intended users, task, and constraints before prescribing UI.
For a chatbot, account for conversation flow, loading and error states, empty
results, source references, keyboard/mobile use, and accessible labels.

Ground the design in the current API and authentication behavior. The current
`POST /api/query` returns an answer and source references; it is not an
OpenAI-compatible chat-completions endpoint. Do not propose a UI that assumes
otherwise. Distinguish user-facing chat from operator tools such as Portainer.
Create design artifacts only when requested.

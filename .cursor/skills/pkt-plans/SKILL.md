---
name: pkt-plans
description: Update architecture or roadmap docs under plans/. Use when editing plans/*.md or keeping design docs aligned with code.
---

# pkt-plans

Use this skill when updating architecture or roadmap documents under `plans/`.

## Scope
- `plans/*.md` and `plans/future/*.md`.
- Alignment between design docs and implemented behavior.

## Rules
- Prefer incremental edits over rewriting entire documents.
- Mark assumptions and unresolved decisions clearly.
- Include concrete file/module references for implementation notes.
- If scope or ordering changes, update phase and dependency language explicitly.

## Documentation checklist
1. Verify current code state before describing it.
2. Update only the affected plan sections.
3. Keep terminology consistent across plan files.

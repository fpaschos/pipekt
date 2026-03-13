---
name: pkt-core
description: Implement or refactor core pipeline behavior in pipekt commonMain. Use when editing PipelineDefinition, operators, source adapters, or serialization in pipekt/src/commonMain.
---

# pkt-core


Use this skill when implementing or refactoring core pipeline behavior in `pipekt/src/commonMain`.

## Scope

- `PipelineDefinition`, operator composition, source adapters, payload serialization boundaries.
- Public API surface and backwards compatibility.

## Rules

- Keep common code multiplatform-safe; avoid platform-specific assumptions in `commonMain`.
- Prefer explicit, typed contracts over implicit behavior.
- Preserve binary and source compatibility unless the user explicitly requests a breaking change.
- Keep data flow predictable; avoid hidden state in operators.

## Change checklist

1. Confirm the behavior and API contract before editing.
2. Make minimal, targeted changes in `commonMain`.
3. Update KDoc when public behavior changes.
4. Add or update tests for the changed behavior.

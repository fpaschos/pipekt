---
name: pkt-core
description: Implement or refactor core pipeline behavior in pipekt commonMain. Use when editing PipelineDefinition, operators, source adapters, or serialization in pipekt/src/commonMain.
---

# pkt-core


Use this skill when implementing or refactoring core pipeline behavior in `pipekt/src/commonMain`.

## Scope

- `PipelineDefinition`, operator composition, source adapters, payload serialization boundaries.
- Public API surface and backwards compatibility. Public API must be **extensively documented** (KDoc).

## Rules

- Keep common code multiplatform-safe; avoid platform-specific assumptions in `commonMain`.
- Prefer explicit, typed contracts over implicit behavior.
- Preserve binary and source compatibility unless the user explicitly requests a breaking change.
- Keep data flow predictable; avoid hidden state in operators.

## Change checklist

1. Confirm the behavior and API contract before editing.
2. Make minimal, targeted changes in `commonMain`.
3. **Extensive KDoc is required:** All public types, functions, parameters, and return values in `commonMain` must have KDoc covering purpose, contract/usage where relevant, and parameters/returns. When adding or changing public API, add or update KDoc to this standard.
4. Add or update tests for the changed behavior.

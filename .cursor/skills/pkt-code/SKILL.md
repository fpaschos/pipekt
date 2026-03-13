---
name: pkt-code
description: Use when implementing or refactoring Kotlin Multiplatform-safe library code in pipekt (commonMain and platform source sets), including core pipeline behavior, operators, adapters, and serialization, and when validating code against plans and documentation.
---

# pkt-code

Use this skill when creating or validating **library code** in pipekt across Kotlin Multiplatform targets:

- `pipekt/src/commonMain` core logic and contracts.
- Platform-specific source sets (JVM/JS/Native/etc.) that must stay **KMP-safe** and consistent with `commonMain`.

## Scope

- `PipelineDefinition`, operator composition, source adapters, payload serialization boundaries, and related core primitives.
- Public API surface and backwards compatibility for all KMP targets. Public API must be **extensively documented** (KDoc).
- Supporting code in platform source sets, as long as it participates in the library surface or core behavior.

## Alignment with plans and docs

- Before changing or adding behavior, check relevant docs under `plans/` (for example stream contracts, architecture, or delivery phases). Code must **not contradict** those contracts.
- For behavior changes, ensure there is a clear link to either:
  - a documented contract/requirement in `plans/`, or
  - a concrete bug/feature request from the current task.
- When you extend behavior beyond what is documented, either:
  - keep it internal and clearly marked as such, or
  - work with `pkt-plans` to update the relevant plan document so code and plans stay aligned.

## Rules

- Keep common code multiplatform-safe; avoid platform-specific assumptions in `commonMain`.
- Prefer explicit, typed contracts over implicit behavior.
- Preserve binary and source compatibility unless the user explicitly requests a breaking change.
- Keep data flow predictable; avoid hidden state in operators.

## Time and Duration

- **Never use `Long` for time values or intervals.** Always use `kotlin.time` types:
  - Timestamps and instants: `kotlin.time.Instant` (via `Clock.System.now()`)
  - Durations and intervals: `kotlin.time.Duration` (e.g. `10.milliseconds`, `30.seconds`, `5.minutes`)
  - Time sources: `kotlin.time.Clock` — never `System.currentTimeMillis()` or `System.nanoTime()`
- This applies to all public API parameters, return types, and data class fields. `Long`-epoch fields (e.g. `startedAt: Long`) that already exist in the codebase must not be widened without an explicit task to migrate them.
- `delay(Duration)` is preferred over `delay(Long)` in coroutines.

## Documentation (code-level)

- **KDoc at production quality:** All public types, functions, parameters, and return values in `commonMain` and platform source sets must have KDoc covering purpose, contract/usage where relevant, parameters, and returns.
- Document **behavioral contracts** and edge cases in KDoc, especially where they are derived from `plans/` docs.
- Internal helpers that encode non-trivial invariants or KMP-specific constraints should also have KDoc explaining intent and constraints (not just restating the code).

## Change checklist

1. Confirm the behavior and API contract before editing, using `plans/` where applicable.
2. Make minimal, targeted changes in `commonMain` and only necessary, mirrored changes in platform source sets.
3. Update KDoc to match the new or refined behavior, for both public API and important internal helpers.
4. Add or update tests for the changed behavior using **pkt-tests** (Kotest, KMP-safe assertions and contracts).


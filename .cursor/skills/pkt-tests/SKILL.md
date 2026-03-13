---
name: pkt-tests
description: Testing standards for pipekt: create and validate Kotest tests for behavior and contracts, aligned with plans and requested feature. Use when adding or changing pipeline/operator tests, fixing bugs, or checking test usefulness and coverage in pipekt.
---

# pkt-tests

Use this skill for **creation** and **validation** of tests. Align tests with `plans/` and the requested feature; keep them small and elegant; follow the interactive approve-then-implement flow.

## Framework: Kotest only

- **Only Kotest tests are acceptable.** Do not introduce or use JUnit, Kotlin Test, or other test frameworks in pipekt. Use `io.kotest` (FunSpec, should-matchers, etc.) and the project’s existing Kotest setup.
- **Rely on Kotest best practices** for structure, assertions, and style (spec styles, matchers, inspectors, etc.).

## Test names: readable, behavior-focused

- Use **readable, sentence-style test names** that describe the scenario and expected outcome. Prefer names that read like a short sentence.
- **Good:** `"pipeline with filter step and persistEach builds successfully"`, `"left is returned when step config is invalid"`.
- **Bad:** `"runRecordHasRequiredFields"`, `"test1"`, `"works"`. Avoid camelCase “function names” or vague labels.

## When to use

- **Creation:** Adding or changing tests for a given feature, bug, or contract. Follow the creation flow (propose → approve → implement → explain).
- **Validation:** Checking existing or proposed tests for usefulness, alignment with plans and task, and overengineering. Follow the validation flow (list → check → report → suggest/approve).

## Scope

- `pipekt/src/commonTest` for pipeline definition and operator semantics.
- Regression coverage for operator edge cases.

## Alignment (plans + requested feature)

Before writing or approving tests, consider relevant docs under `plans/` (e.g. `plans/streams-contracts-v1.md`, `plans/streams-core-architecture.md`). Tests must align with:

- **Plans:** Contract and behavior described there (e.g. StepFn, PipelineDefinition, INFINITE-only). Do not add tests for deferred/future scope (e.g. BOUNDED, physical-plan API) unless the task explicitly requests it.
- **Requested feature:** Every test (or test class) must be traceable to a stated feature request, bug report, or contract from the user/task, or to a concrete edge case or success path from the plans.

**Checklist:** Does this test validate something from the task or from plans? If not, remove or rewrite.

## Useful and not overengineered

- **Useful:** The test either (a) covers a stated requirement or edge case from the task or plans, or (b) protects a documented contract (e.g. operator semantics, serialization boundary). If you cannot state in one sentence what contract or edge case the test validates, the test is not useful.
- **Not overengineered:** Prefer one behavior per test; minimal setup; no unnecessary abstraction or helpers unless shared across many tests. Prefer small, readable test names and bodies. If the same behavior can be covered by a simpler test, the simpler one wins.

Rules:

- One sentence usefulness: for each test, you must be able to say what it validates.
- One behavior per test when practical; avoid kitchen-sink tests.
- Prefer the simplest test that covers the behavior; drop extra setup or indirection.

## Interactive flow

### Creation flow (required)

1. **Propose:** Propose **what** will be tested: list behaviors/edge cases and which plan or task item they map to. No code yet, or only minimal snippets if needed for clarity.
2. **Approval:** Ask explicitly: *"Approve this test scope? (yes / adjust: …)"* and wait for user confirmation or adjustment.
3. **Implement:** After approval, add or update tests; keep them small and elegant.
4. **Explain:** Briefly state what each test does and how it ties to the plan/task (one line per test or per class).

### Validation flow

1. **List:** List what the existing or proposed tests claim to cover.
2. **Check:** Check against plans and the task (alignment, usefulness, overengineering).
3. **Report:** Report gaps: missing coverage, overengineered, or out-of-scope tests.
4. **Suggest:** Suggest concrete changes; if appropriate, ask for approval before applying.

## Rules

- Add tests that fail before the fix and pass after it.
- Use readable, sentence-style test names (see **Test names** above).
- Cover success and edge/error paths where relevant.
- Keep tests deterministic; do not rely on timing-sensitive assertions.

## Documentation

- Test code must be documented with **KDoc at the same level of detail and care as production code**.
- Test classes must be **extensively documented** at class level. For each test class, add KDoc that describes the contract and behavior coverage (what the tests validate, which edge cases and success paths are covered). When adding or changing behavior coverage, add or update this class-level KDoc so the skill and reviewers can see scope at a glance.
- Helper functions used inside tests (e.g. factory/builders, common assertions) should also have KDoc explaining **intent and contracts**, not mechanics, just like in production code.

### Arrow and Kotest assertions

- **Arrow types (Option, Either, Validated):** Prefer fetching Arrow docs from **context7** (user-context7 MCP) when you need API or usage details. For Kotest matchers on Arrow types, use **https://kotest.io/docs/assertions/arrow.html** (e.g. `shouldBeSome`, `shouldBeNone`, `shouldBeRight`, `shouldBeLeft`, `shouldBeValid`, `shouldBeInvalid`). Always prefer Kotest Arrow matchers over manual unwrapping in tests.

## Minimum validation

1. Run targeted tests for changed areas.
2. Run full `:pipekt:check` before finalizing substantial changes.
3. If any tests are skipped, call out why.

## Cross-reference

- When the task involves plan-backed behavior, use or read the **pkt-plans** skill for plan structure. pkt-tests does not edit plans; it only validates tests against them.

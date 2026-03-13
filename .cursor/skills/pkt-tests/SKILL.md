---
name: pkt-tests
description: Testing standards for pipekt: add or update tests for behavior and contracts. Use when changing pipeline or operator behavior, fixing bugs, or working in pipekt/src/commonTest.
---

# pkt-tests

Use this skill when adding behavior, fixing bugs, or changing contracts.

## Scope
- `pipekt/src/commonTest` for pipeline definition and operator semantics.
- Regression coverage for operator edge cases.

## Rules
- Add tests that fail before the fix and pass after it.
- Prefer behavior-focused test names that describe inputs and outputs.
- Cover success and edge/error paths where relevant.
- Keep tests deterministic; do not rely on timing-sensitive assertions.

## Minimum validation
1. Run targeted tests for changed areas.
2. Run full `:pipekt:check` before finalizing substantial changes.
3. If any tests are skipped, call out why.

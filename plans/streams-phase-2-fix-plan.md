# Streams Phase 2 Fix Plan

## Purpose

This document turns the current implementation review into a concrete **Phase 2 remediation plan** for the new `streams` runtime. It focuses on gaps between the active plans and the implementation that exists now, and it defines the work required before Phase 3 (durable Postgres store) should proceed.

This is a technical planning document, not a backlog dump. Each item includes:

- the current problem
- the required implementation change
- required tests
- estimated complexity

This document supplements:

- [streams-delivery-phases.md](streams-delivery-phases.md)
- [streams-delivery-additions.md](streams-delivery-additions.md)
- [streams-contracts-v1.md](streams-contracts-v1.md)
- [streams-technical-requirements.md](streams-technical-requirements.md)

---

## Scope

The fixes in this document are all **Phase 2** concerns:

- runtime correctness
- retry semantics
- restart/recovery behavior
- store contract conformance needed by the runtime
- missing tests required to freeze the behavior before Postgres work

This document does **not** add new v1 features. It only closes gaps against the already accepted MVP plans.

---

## Summary Table

| Fix | Problem area | Required before Phase 3 | Estimated complexity |
| --- | --- | --- | --- |
| F1 | Missing `lastErrorJson` contract | Yes | Small |
| F2 | Incorrect `attemptCount` semantics across steps | Yes | Medium |
| F3 | Runtime dies on store/infra failure | Yes | Medium |
| F4 | Startup does not reclaim expired leases before workers run | Yes | Small |
| F5 | Missing explicit Phase 2 behavior tests | Yes | Medium |

Complexity guidance:

- **Small**: localized contract/model change; low refactor risk; usually 1-2 files plus tests
- **Medium**: behavior spans multiple runtime/store components; moderate test surface; some design choices must be made explicit
- **Large**: broad API or architecture change across packages; migration or major refactor required

---

## F1 — Implement `lastErrorJson` End To End

### Problem

The plans require `WorkItem.lastErrorJson` as the MVP's compact error history after removal of the separate `attempts` table. The current implementation documents the field in comments but does not actually model or persist it.

Current impact:

- fatal failures lose their durable error payload
- filtered terminal outcomes lose their durable reason
- the store contract is weaker than the accepted v1 plan
- Phase 3 schema work would otherwise start from the wrong entity shape

### Required changes

1. Add `lastErrorJson: String?` to `WorkItem`.
2. Update `checkpointFailure` to set `lastErrorJson = errorJson`.
3. Update retryable failure checkpointing to preserve or set the latest error value consistently.
4. Decide and document filtered behavior:
   - recommended: `checkpointFiltered` also writes the filter reason into `lastErrorJson`
   - this matches current plan language that the MVP keeps compact terminal outcome detail on the item
5. Ensure success checkpointing clears `lastErrorJson` when an item advances or completes successfully.

### Required tests

- store contract test: fatal failure writes `lastErrorJson`
- store contract test: retryable failure writes `lastErrorJson` and keeps payload
- store contract test: filtered item records reason in `lastErrorJson`
- runtime test: step-raised fatal error is visible on the final item state

### Estimated complexity

**Small**

Why:

- one model addition
- straightforward checkpoint behavior changes
- limited runtime coupling

### Implementation notes

- The field should remain nullable.
- This fix should land before any Postgres schema work so the initial schema matches the real contract.

---

## F2 — Correct `attemptCount` Semantics Per Step

### Problem

The current implementation carries `attemptCount` forward across step transitions. That conflicts with the current code's own documentation ("attempts for the current step") and causes retry exhaustion and `StepCtx.attempt` values in later steps to be wrong.

Current failure mode:

- step A retries twice, then succeeds
- item advances to step B with `attemptCount = 2`
- step B begins as attempt 3 instead of attempt 1
- retry policy for step B can be exhausted early

### Required changes

1. Freeze the intended semantic in the plans and code:
   - `attemptCount` is **per current step**, not cumulative across the full pipeline
2. Update `checkpointSuccess(item, outputJson, nextStep)`:
   - if `nextStep != null`, reset `attemptCount` to `0`
   - if `nextStep == null`, increment for the final successful execution of the current step
3. Keep `checkpointFailure` and `checkpointFiltered` incrementing within the current step.
4. Keep `StepCtx.attempt = item.attemptCount + 1`.
5. Audit runtime retry logic to ensure retry exhaustion uses step-local attempt state only.

### Required tests

- runtime test: step A retries, step B starts at attempt 1
- runtime test: later-step retry policy is unaffected by earlier-step failures
- store test: non-terminal success resets `attemptCount` on step advance
- store test: terminal success increments the final step's count correctly

### Estimated complexity

**Medium**

Why:

- behavior change sits at the runtime/store boundary
- existing tests likely assume only single-step flows
- this is correctness-critical and needs explicit contract freezing

### Implementation notes

- If a future observability feature needs pipeline-global attempt history, that belongs in the deferred `attempts` table, not in `WorkItem.attemptCount`.

---

## F3 — Make Runtime Loops Resilient To Store/Infrastructure Failures

### Problem

The technical requirements explicitly say store failures must become controlled pauses with retry/backoff, not dead coroutines. The current actor-backed runtime lets ingestion, worker, or watchdog actors fail hard on exceptions from store operations or source/store boundaries.

Current impact:

- transient store outage can terminate pipeline execution
- a single failing loop can bring down the runtime actor
- restart safety exists at the store level, but loop survivability does not

### Required changes

1. Add loop-level try/catch around:
   - ingestion tick work
   - step worker tick work
   - lease reclaimer tick work
2. On caught infrastructure failure:
   - log or surface enough context for diagnosis
   - apply bounded retry backoff before scheduling the next tick
   - do not terminate the actor
3. Introduce a small runtime-local backoff policy for infra failures:
   - recommended start: 100 ms
   - cap: 5 s
   - reset to normal cadence after a successful store/source interaction
4. Keep item-level `ItemFailure` behavior unchanged. This fix is only for infra exceptions escaping the runtime loop.
5. Optionally expose lightweight runtime health state later; do not block this fix on observability API design.

### Required tests

- runtime test: `appendIngress` throws once, ingestion recovers and continues
- runtime test: `claim` throws once, worker recovers and later processes items
- runtime test: `reclaimExpiredLeases` throws once, watchdog survives and retries
- runtime test: runtime remains inspectable/listed after transient infra failure

### Estimated complexity

**Medium**

Why:

- crosses actor lifecycle and runtime scheduling behavior
- needs a deliberate distinction between item failures and infra failures
- requires new test doubles that fail deterministically

### Implementation notes

- Keep this behavior actor-local. Do not add a new public API unless it is actually needed.
- This work should align with the guidance already written in [streams-technical-requirements.md](streams-technical-requirements.md).

---

## F4 — Reclaim Expired Leases Before Starting Workers

### Problem

The plans require restart recovery to reclaim expired leases before re-executing active runs. The current startup flow starts workers immediately and relies on the watchdog's first delayed tick.

Current impact:

- expired `IN_PROGRESS` items remain invisible during the startup window
- startup behavior does not match the documented `resumeRuns` requirement
- recovery depends on watchdog timing instead of deterministic startup recovery

### Required changes

1. Add a startup recovery step before ingress/step workers are spawned.
2. Call `store.reclaimExpiredLeases(now, limit)` once during startup.
3. Use a loop until fewer than `limit` items are reclaimed, so large stuck sets are fully drained:

```kotlin
do {
    reclaimed = store.reclaimExpiredLeases(now = Clock.System.now(), limit = config.workerClaimLimit)
} while (reclaimed.size == config.workerClaimLimit)
```

4. Only after reclaim completes should the runtime spawn ingress and step workers.
5. Reflect this in docs as the actor-based equivalent of the earlier `resumeRuns` requirement.

### Required tests

- runtime test: expired item is reclaimed before worker ticks begin
- runtime test: startup recovery handles more expired items than a single reclaim batch

### Estimated complexity

**Small**

Why:

- change is localized to startup orchestration
- behavior is already supported by the store SPI

### Implementation notes

- The runtime still needs the periodic watchdog afterward. Startup reclaim is not a replacement for the watchdog.

---

## F5 — Add Explicit Phase 2 Contract Tests

### Problem

Some of the required behavior is only partially covered or covered incidentally. The current suite passes while important contract violations remain.

### Required changes

Add focused tests that fail for the current broken behaviors and remain valuable when the Postgres store is added:

1. `lastErrorJson` persistence tests
2. step-local `attemptCount` tests across multi-step pipelines
3. startup reclaim-before-workers test
4. loop-survival tests for transient infrastructure exceptions
5. retained existing tests for:
   - backpressure at `maxInFlight`
   - retry backoff timing
   - watchdog reclaim/resume

### Estimated complexity

**Medium**

Why:

- test doubles and orchestration cases will take more effort than the corresponding code changes
- these tests are the guardrail that prevents Phase 3 from baking in broken semantics

### Implementation notes

- Prefer tests that assert externally observable behavior rather than actor internals.
- These tests should remain mostly store-agnostic so they can be rerun against the Postgres implementation later.

---

## Recommended Delivery Order

Apply the fixes in this order:

1. **F1 — `lastErrorJson`**
2. **F2 — `attemptCount` semantics**
3. **F4 — startup reclaim before workers**
4. **F3 — loop resilience on infra failure**
5. **F5 — fill remaining contract tests**

Reasoning:

- F1 and F2 freeze the entity/runtime semantics that Postgres must implement.
- F4 is small and closes a correctness gap in restart behavior.
- F3 is the most behaviorally sensitive runtime change and is easier to implement after the store/runtime contracts are corrected.
- F5 finalizes the guardrails once the target semantics are settled.

---

## Exit Criteria For Phase 2

Phase 2 should not be treated as complete until all of the following are true:

- `WorkItem` includes `lastErrorJson` and store implementations persist it correctly
- `attemptCount` semantics are explicitly step-local and proven by tests across step transitions
- startup recovery reclaims expired leases before worker execution begins
- ingestion, worker, and watchdog loops survive transient infrastructure failures with bounded backoff
- the test suite contains direct coverage for these behaviors

Only after these are true should Phase 3 start as the durable implementation of a stable contract.

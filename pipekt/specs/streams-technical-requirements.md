# Streams Technical Requirements

**Status:** Active technical guidance document.

**Purpose:** Capture operational guidance, tuning defaults, infrastructure behavior, and implementation recommendations that complement the stable contracts.

**Precedence:** This document does not override [streams-contracts-v1.md](./streams-contracts-v1.md). It should be read after contracts and current implementation status, and alongside [streams-phase-2-fix-plan.md](./streams-phase-2-fix-plan.md) for unresolved gaps.

## Purpose and scope

This document is the **technical requirements reference** for orthogonal runtime concerns that apply before and during Phase 2 (Runtime and Backpressure): default values and recommended ranges, error handling strategy (internal vs external), Arrow usage and ergonomics, database performance options, pipeline registry/management, and runtime code organization.

**Readers:** Implementers and reviewers working on `pipekt` runtime and application wiring.

**Relationship to phases:** Many items here are implementable before Phase 2. Phase 2 remains "Runtime and Backpressure" as defined in [streams-delivery-phases.md](streams-delivery-phases.md). This TR supplements [streams-delivery-phases.md](streams-delivery-phases.md), [streams-contracts-v1.md](streams-contracts-v1.md), and [streams-phase-2-fix-plan.md](streams-phase-2-fix-plan.md), and recommends small amendments to those documents where noted.

---

## Default values and recommended ranges

All runtime and definition knobs with current code defaults, test-friendly values, and production recommendations:

| Knob | Current default (code) | Test / in-memory | Production recommended |
|------|------------------------|------------------|-------------------------|
| `workerPollInterval` | 10 ms | 10–50 ms | 100–500 ms; or adaptive backoff when `claim` returns empty |
| `watchdogInterval` | 50 ms | 50–200 ms | 1–5 s; must be well below `leaseDuration` |
| `leaseDuration` | 30 s | 10–30 s | 30–60 s; 2–5 min for long-running steps |
| Claim `limit` (per worker poll) | 10 | 5–10 | 10–64; high throughput 50–200 |
| `maxInFlight` | (none; required in definition) | 500–2k | 5k–25k medium volume; 25k–100k high volume |
| `retentionDays` | 30 | 30 | 7–14 high volume; 30–90 audit/compliance |

**Rules:**

- `maxInFlight` is always set explicitly at pipeline definition time; there is no engine default. A reasonable starting point is `workerConcurrency × stepCount × 2`.
- Production wiring should override `watchdogInterval` (e.g. 5 s) and optionally `workerPollInterval`. The code defaults of 10 ms / 50 ms are intentionally test-friendly.
- Total `maxInFlight` across all pipelines on a server should be evaluated against DB capacity (connection pool, `countNonTerminal` query cost). For 3–5 concurrent pipelines, keep the total well under 500k rows non-terminal at any time.
- `leaseDuration` should exceed the expected worst-case step execution time; the watchdog is a recovery mechanism, not a normal execution path.
- current implementation note: `maxInFlight` currently behaves as a soft runtime throttle rather than a store-enforced hard cap; see [streams-phase-2-fix-plan.md](./streams-phase-2-fix-plan.md).

**References:** `PipelineRuntime` constructor, `PipelineDefinition.maxInFlight`, `PipelineDefinition.retentionDays`, and the Phase 2 backpressure notes in [streams-phase-2-fix-plan.md](streams-phase-2-fix-plan.md).

---

## Error handling

### Internal (store / infrastructure)

**Single failure class for the runtime.** All store calls that fail (connection loss, timeout, driver exception, serialization failure) are treated as "store unavailable" at the runtime level. The runtime does not distinguish failure subtypes for control flow.

**No `Result`/`Either` on `DurableStore` SPI.** The `DurableStore` interface remains exception-based. sqlx4k and JDBC-style drivers surface errors as exceptions; wrapping them in `Either` at the SPI boundary adds a second error channel without benefit and forces every implementation and caller to handle both Arrow and exception paths.

**Runtime responsibility — loop-level try/catch.** Each loop body (ingestion, worker, watchdog) must be wrapped in try/catch. On any `Throwable` thrown from a store call:
1. Log the error with enough context (pipeline name, loop type, exception message).
2. Optionally increment a metric (e.g. `store.errors.count{pipeline, loop}`).
3. Apply exponential backoff before retrying (e.g. 100 ms → 1 s → 5 s, capped). Use `delay(Duration)`.
4. Continue the loop — never let the loop coroutine die on an infra failure.

This ensures infra outages become **controlled pauses** rather than silent stalls (loops die, items stuck `IN_PROGRESS`, backpressure locked).

**Optional health state.** A per-runtime `StoreState` (e.g. `Healthy` / `Unavailable`) can be exposed for metrics or the Phase 6 health endpoint. It flips to `Unavailable` when a loop catches a store exception and back to `Healthy` after the next successful store call.

### External (step / business)

Step-level outcomes are already modeled via `ItemFailure` and Arrow `Raise<ItemFailure>` in step functions. `streams-contracts-v1.md` states that runtime-owned failures (lease loss, serialization error, store failure) are **not** modeled via `Raise<ItemFailure>` — they are handled outside step functions by the runtime loops.

**When to use Arrow.** Use `Raise<ItemFailure>` and Arrow `Either` only for item-level outcomes:
- Retryable transient failures (`isRetryable() = true` → retry with backoff).
- Fatal permanent failures (`isRetryable() = false` → `checkpointFailure(..., retryAt = null)`).
- Filter outcomes (`isFiltered() = true` → `checkpointFiltered`).

**Ergonomics rule.** Keep Arrow for the step API only. Use exceptions and try/catch for all infrastructure failures at the runtime boundary. This keeps step code clean (Arrow DSL, typed errors) without polluting the runtime infra path with Either chains.

### Bounded retries and poison

`RetryPolicy.maxAttempts` must be finite (always). After attempts are exhausted or a non-retryable failure is raised, call `checkpointFailure(item, message, retryAt = null)` so the item reaches terminal `FAILED`. The engine must never retry a terminal-failed item; the "retry forever" failure mode is prevented by this rule and by the terminal checkpoint.

---

## Payload retention and storage efficiency

`payloadJson` is the runtime payload for the **current** step only. It is not an immutable history record.

Rules:

- each non-terminal successful checkpoint overwrites `payloadJson` with the next-step payload
- terminal checkpoints (`COMPLETED`, `FILTERED`, `FAILED`) must null `payloadJson`
- terminal payload nulling is a required storage/performance behavior, not optional cleanup

Why:

- step payloads may grow substantially after enrichment
- retaining them on terminal items would create avoidable durable row bloat
- nulling terminal payloads keeps storage growth focused on active work rather than historical payload bodies

This optimization must not break restart correctness; terminal items no longer need payload material to continue execution.

---

## Database performance optimization

### NOTIFY/LISTEN

**Scope:** Optional, Postgres-only. No change to `DurableStore` SPI; the optimization lives entirely inside the Postgres store implementation.

**Where to emit NOTIFY.** After any transaction that makes new items claimable:
- `appendIngress` (items enter at the first step).
- `checkpointSuccess` with a non-null `nextStep` (item advances to next step).
- `reclaimExpiredLeases` (stuck items become claimable again).

Emit `NOTIFY work_items_available` (or a per-step / per-pipeline channel name) inside or immediately after the transaction.

**Where to LISTEN.** Worker loops in the Postgres implementation block on `LISTEN work_items_available` with a timeout (e.g. 5 s as safety poll). When the notification arrives, they call `claim(...)`. This replaces the tight `delay(workerPollInterval)` loop with "sleep until there is work", eliminating empty `SELECT ... FOR UPDATE SKIP LOCKED` calls during idle periods.

**Stores without NOTIFY.** `InMemoryStore` and other store implementations keep the current polling model. Optionally, add exponential backoff in worker loops when `claim` returns empty (back off up to ~1–5 s before resuming normal interval). No SPI change is required.

### pgmq (sqlx4k)

**Scope:** Optional, application/Postgres layer only. Not in `commonMain`. No `DurableStore` SPI change.

**Pattern 1 — Ingress buffer.** The source adapter or ingestion component writes to a pgmq queue instead of directly calling `appendIngress`. A separate reader dequeues batches and calls `DurableStore.appendIngress`. This absorbs spiky source ingress without hammering `work_items` directly.

**Pattern 2 — Per-step dispatch.** After writing a claimable item to `work_items` (via `appendIngress` or `checkpointSuccess`), the Postgres store also enqueues the work item ID to a pgmq queue (one queue per step or per pipeline). Workers block on pgmq dequeue, then call a narrow claim-by-ID operation (single-row `SELECT ... FOR UPDATE` on the specific item) instead of scanning all `PENDING` rows. This trades "many empty scans" for "few targeted point-lookups".

**Summary.** No store is required to support NOTIFY or pgmq. The `DurableStore` SPI stays store-agnostic. Both optimizations are implementation-level choices that reduce DB pressure without changing the engine's correctness contracts.

---

## Pipeline registry and management

A single `PipelineRuntime` owns execution of one `PipelineDefinition`. A server running 3-5 or more concurrent pipelines needs a **central component** above the runtime to own, start, stop, and enumerate pipeline instances.

**Where it lives:** In the current implementation this role is provided by `PipelineOrchestrator` in `pipekt.runtime.new`. Applications and frameworks own the orchestrator instance, but the orchestration API itself is part of the library.

**Concept.** `PipelineOrchestrator` holds the active executable registry and provides:
- `startPipeline(definition, planVersion, config)` - create and start a pipeline runtime; register it.
- `stopPipeline(executableId, timeout)` - stop and deregister one active executable.
- `inspectPipeline(executableId)` / `listActivePipelines()` - inspect current runtime-owned state.
- `stopAll(timeout)` / `shutdown(timeout)` - stop all active executables and terminate orchestrator-owned background work.

Pipeline health and in-flight counts can be derived via `PipelineExecutableSnapshot`, `DurableStore.findAllActiveRuns(pipeline)`, and `DurableStore.countNonTerminal(runId)` for observability.

**Concurrency.** The current implementation uses an actor-backed orchestrator. Registry mutation and runtime ownership are serialized through actor messages, which removes the old shared mutable `PipelineRuntime` ownership problem.

**Reference:** `runtime/new/actor-based-runtime.md`, `streams-phase-2-fix-plan.md`, and Phase 6 lifecycle in `streams-delivery-phases.md`.

---

## Runtime code organization (reduce duplication)

### Duplication today

[`PipelineRuntime.kt`](../src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/new/PipelineRuntime.kt) still has two paired sets of near-identical code:

**Worker launch duplication.** `launchStepWorker` and `launchFilterWorker` share the same structure: `store.claim(stepName, runId, 10, leaseDuration, workerId)` → `for (item in claimed) executeX(...)` → `delay(workerPollInterval)`. Only the `executeX` call differs.

**Execution duplication.** `executeStep` and `executeFilter` both: deserialize `item.payloadJson` via `serializer.deserialize`, build `StepCtx` via `buildCtx`, run `either { with(ctx) { fn(input) } }`, and fold the `Either`. They differ only in the function type (step fn vs predicate) and how the `Either` result is turned into a store checkpoint call.

Adding a new operator type (e.g. `MapDef`, `SplitDef`) would require a third `launchXWorker` and a third `executeX` pair, compounding the duplication.

### Pattern 1: single worker loop + ExecutableOp

Introduce a private sealed interface `ExecutableOp`:

```kotlin
private sealed interface ExecutableOp {
    val stepName: String
    suspend fun executeAndCheckpoint(item: WorkItem, nextStepName: String?)
}
```

Implement one class per operator kind:

```kotlin
private inner class StepExecutable(val def: StepDef<Any?, Any?>) : ExecutableOp {
    override val stepName = def.name
    override suspend fun executeAndCheckpoint(item: WorkItem, nextStepName: String?) {
        // deserialize, run fn, checkpoint success or call handleFailure
    }
}

private inner class FilterExecutable(val def: FilterDef<Any?>) : ExecutableOp {
    override val stepName = def.name
    override suspend fun executeAndCheckpoint(item: WorkItem, nextStepName: String?) {
        // deserialize, run predicate, checkpoint filtered or success or call handleFailure
    }
}
```

Replace the two `launchXWorker` functions with one:

```kotlin
private fun launchWorker(exe: ExecutableOp, nextStepName: String?) {
    jobs += scope.launch {
        while (isActive) {
            val claimed = store.claim(exe.stepName, runId, workerClaimLimit, leaseDuration, workerId)
            for (item in claimed) {
                exe.executeAndCheckpoint(item, nextStepName)
            }
            delay(workerPollInterval)
        }
    }
}
```

`launchWorkerLoops()` only maps operators to `ExecutableOp` and calls `launchWorker`. Adding a future operator type = add one `ExecutableOp` implementation; the launch loop stays unchanged.

### Pattern 2: shared "run step fn" helper

Factor the Arrow + context boilerplate into a private generic helper:

```kotlin
private suspend fun <I, R> runStepFn(
    item: WorkItem,
    stepName: String,
    inputType: KType,
    block: suspend context(Raise<ItemFailure>, StepCtx) (I) -> R,
): Either<ItemFailure, R> {
    val input = serializer.deserialize<I>(item.payloadJson!!, inputType)
    val ctx = buildCtx(item, stepName)
    return either { with(ctx) { block(input) } }
}
```

`StepExecutable.executeAndCheckpoint` and `FilterExecutable.executeAndCheckpoint` then become thin interpreters of the resulting `Either`, with no repeated deserialization or context-building code.

### Location and visibility

Keep `ExecutableOp`, `StepExecutable`, `FilterExecutable`, and `runStepFn` **private** inside `PipelineRuntime.kt`. No new public API. The existing public surface (`constructor`, `start`, `stop`, `definition`, `store`, `serializer`, `scope`, `planVersion`, `workerPollInterval`, `watchdogInterval`) is unchanged.

---

## Alignment and recommended plan changes

### Cross-references

This TR references:
- [streams-delivery-phases.md](streams-delivery-phases.md): Phase 1/2 scope, Phase 6 lifecycle.
- [streams-phase-2-fix-plan.md](streams-phase-2-fix-plan.md): remaining runtime correctness fixes and contract gaps before Phase 3.
- [streams-contracts-v1.md](streams-contracts-v1.md): StepFn contract, StepCtx fields, runtime failures not via `Raise<ItemFailure>`.

### Recommended updates to existing plans

- **streams-delivery-phases.md:** Keep Phase 6 wording aligned with the current `PipelineOrchestrator` API in `pipekt.runtime.new`, while leaving framework ownership and wiring concerns in that phase.
- **README.md:** Keep `streams-technical-requirements.md` in the list of active MVP spec documents before `streams-phase-2-fix-plan.md`.

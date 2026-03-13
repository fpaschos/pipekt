# Streams Contracts V1

## Summary

This document freezes the v1 contract set for the new `streams` library. These contracts are intentionally minimal and are specific enough to support an in-memory implementation first, followed by a durable Postgres-backed runtime.

The v1 design requires a logical definition/runtime separation, but only in a minimal form:

- `PipelineDefinition` is the validated logical shape
- runtime may derive an internal executable representation from it

V1 does not require:

- a public physical-plan API
- optimizer passes
- DataFusion-style planning layers
- `BOUNDED` pipeline mode (deferred — see `future/streams-future-planning-features.md`)

---

## Pipeline Execution Mode

V1 supports only `INFINITE` pipelines:

- continuous ingress from a long-lived source (e.g. AMQP queue)
- run is long-lived; no terminal state expected
- a single `runId` is maintained across restarts; new items are appended continuously
- items are individually terminal (`COMPLETED`, `FILTERED`, `FAILED`); the run itself is never finalized

`BOUNDED` mode — finite item sets per run with barrier and finalizer operators — is explicitly deferred to a future version.

---

## Core Type Contracts

### Step Function

```kotlin
typealias StepFn<I, O> = suspend context(Raise<ItemFailure>, StepCtx) (I) -> O
```
(Engine uses `arrow.core.raise.Raise`; context receiver order: `Raise<ItemFailure>`, then `StepCtx`.)

Rules:

- `StepFn` must be `suspend` — step functions call suspending APIs (HTTP clients, DB access, `delay`)
- `I` is the typed input payload for the current step
- `O` is the typed output payload for the next step
- The error channel is fixed to `ItemFailure` — it is not a generic type parameter
- `ItemFailure` is an open interface; callers implement it with domain types and raise them via `Raise<ItemFailure>` (subtype accepted by the context receiver)
- Arrow's `withError` can map typed domain errors to `ItemFailure` at a step boundary for callers who need typed routing before the runtime sees the error
- A generic `E` type parameter cannot be inferred from context receivers in Kotlin — callers would always have to annotate it explicitly; fixing to `ItemFailure` removes this annotation burden while preserving full extensibility via implementation
- This follows the same pattern as ktkit's `ErrorSpec`: one fixed error channel owned by the framework, extended by callers via interface implementation
- runtime-owned failures such as lease loss, serialization failure, or store failure are not modeled via `Raise<ItemFailure>` — they are handled outside step functions

### Step Context

`StepCtx` must contain only engine-level data required by user steps:

- `pipelineName`
- `runId`
- `itemId`
- `itemKey`
- `stepName`
- `attempt`
- `startedAt`
- `metadata`

Rules:

- no kt-framework types
- no transport-specific types
- no direct dependency on current `ExecContext`

### Pipeline Definition

`PipelineDefinition` represents a validated logical pipeline.

Required responsibilities:

- pipeline name
- source definition
- ordered operator definitions
- serialization boundary metadata
- `maxInFlight: Int` — maximum number of non-terminal items (`PENDING` + `IN_PROGRESS`) allowed across all steps before the ingestion loop pauses
- `retentionDays: Int` — number of days terminal `work_items` rows are retained before the archival job deletes them; default 30; should be set lower for high-volume pipelines

Validation must reject:

- duplicate step names (source name and all operator names must be unique)
- no source defined (at least one call to `source(name, adapter)` required)
- empty pipeline (at least one step, filter, or persistEach required)
- invalid maxInFlight (must be > 0)
- invalid type chain between adjacent operators (each operator's input type must match the previous output; PersistEachDef and FilterDef are type-transparent)

In v1, `PipelineDefinition` is a stable public concept. Any executable runtime plan derived from it may remain internal to the runtime package.

### Pipeline Runtime

`PipelineRuntime` owns execution.

Required responsibilities:

- obtain or create the long-lived run on startup
- resume incomplete runs on restart — must call `reclaimExpiredLeases` before re-executing
- bulk-ingest records from a source adapter (ingestion is separate from execution)
- apply backpressure: pause the ingestion loop when `countNonTerminal(runId) >= maxInFlight`; resume when it drops below the threshold
- run per-step worker loops that claim and execute items independently
- execute steps with retry policy
- run a watchdog loop that periodically calls `reclaimExpiredLeases`

The runtime is the coordinator. It is not the source of truth for progress.

For v1, `PipelineRuntime` may compile a `PipelineDefinition` into an internal executable plan before starting a run. That compilation step must remain lightweight and deterministic; it is not a physical-planner subsystem.

Ingestion and execution are **separate concerns**:

- the ingestion loop: `countNonTerminal check → poll → bulk appendIngress → ack` (pauses if `countNonTerminal >= maxInFlight`)
- the worker loops: one per step, running `claim → execute StepFn → checkpoint` independently
- the watchdog loop: periodically calls `reclaimExpiredLeases`

These three loops run concurrently and are independent of each other.

### Payload Serializer

The engine must not hardcode a serialization library. `PayloadSerializer` is the serialization boundary in `pipekt.core`:

```kotlin
interface PayloadSerializer {
    fun <T> serialize(value: T, type: KType): String
    fun <T> deserialize(json: String, type: KType): T
}
```

Rules:

- the engine holds a `PayloadSerializer` reference and calls it when writing or reading `payloadJson`
- the default implementation uses `kotlinx.serialization`
- KMP targets or users with specific format requirements may supply an alternative implementation
- `KType` is captured at DSL call sites via `inline reified` — this is already required for type-chain validation; serialization is a natural extension of the same mechanism
- step authors never interact with `PayloadSerializer` directly; it is an engine-level concern

---

## Store Contracts

### Durable Store

```kotlin
interface DurableStore {
  // Run lifecycle
  suspend fun getOrCreateRun(pipeline: String, planVersion: String, nowMs: Long): RunRecord
  suspend fun getRun(runId: String): RunRecord?
  suspend fun listActiveRuns(pipeline: String): List<RunRecord>

  // Ingress — bulk, idempotent
  suspend fun appendIngress(runId: String, records: List<IngressRecord<*>>, nowMs: Long): AppendIngressResult

  // Claim — atomic SELECT FOR UPDATE SKIP LOCKED
  suspend fun claim(step: String, runId: String, limit: Int, leaseMs: Long, workerId: String): List<WorkItem>

  // Atomic checkpoints — each is a single transaction (attempt counter + item state)
  suspend fun checkpointSuccess(item: WorkItem, outputJson: String, nextStep: String?, nowMs: Long)
  suspend fun checkpointFiltered(item: WorkItem, reason: String, nowMs: Long)
  suspend fun checkpointFailure(item: WorkItem, errorJson: String, retryAtEpochMs: Long?, nowMs: Long)

  // Backpressure — count of non-terminal items for the ingestion loop
  suspend fun countNonTerminal(runId: String): Int

  // Lease reclaim — resets expired IN_PROGRESS items to PENDING
  suspend fun reclaimExpiredLeases(nowEpochMs: Long, limit: Int): List<WorkItem>
}
```

Contract rules:

- store operations are authoritative for progress
- each checkpoint operation is atomic: it increments `attempt_count`, sets `last_error_json` if applicable, updates `current_step` and `status`, and nulls `payload_json` for terminal items — all in one transaction
- state transitions must be monotonic and reject invalid step rewinds
- `appendIngress` uses `ON CONFLICT (run_id, source_id) DO NOTHING` for idempotent ingress
- `claim` uses `SELECT ... FOR UPDATE SKIP LOCKED` within a single transaction
- `countNonTerminal` returns the count of items with status `PENDING` or `IN_PROGRESS` for the given `runId`; used by the ingestion loop to enforce `maxInFlight` backpressure

### Persistent Entities

Required entity families:

- `RunRecord`
- `WorkItem`

`AttemptRecord` as a separate entity is **not required in the MVP**. Attempt tracking is embedded directly in `WorkItem` via `attemptCount` and `lastErrorJson`. A full `attempts` table is deferred to the observability phase.

#### RunRecord minimum fields

- run id, pipeline name, plan version
- status and timestamps

#### WorkItem minimum fields

- item id, run id, source id (for deduplication)
- current step name
- status: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FILTERED`, `FAILED`
- `payloadJson: String?` — nullable; holds the serialized current-step payload; **nulled at terminal checkpoint**
- `lastErrorJson: String?` — set on `FAILED`, null otherwise
- `attemptCount: Int` — incremented on every checkpoint call
- `leaseOwner: String?` — worker id holding the current lease
- `leaseExpiryMs: Long?` — epoch ms at which the lease expires
- `retryAtMs: Long?` — epoch ms before which a failed item must not be retried

---

## Source Adapter Contract

The source adapter boundary must be generic:

```kotlin
interface SourceAdapter<T> {
  suspend fun poll(maxItems: Int): List<SourceRecord<T>>
  suspend fun ack(records: List<SourceRecord<T>>)
  suspend fun nack(records: List<SourceRecord<T>>, retry: Boolean)
}
```

Rules:

- source records carry payload plus transport metadata
- transport metadata must not leak into `StepFn` signatures
- ack happens only after durable append succeeds
- if append fails, the adapter must not ack those records
- `sourceId` must be the **stable business key extracted from the message payload**, not the broker delivery ID (AMQP delivery tag, Kafka offset, or equivalent)
- broker delivery IDs change on replay; business keys in the payload are stable across replays
- replay safety via `ON CONFLICT (run_id, source_id) DO NOTHING` depends entirely on `sourceId` stability — if `sourceId` is not stable, replay creates duplicate work items

---

## Retry And Failure Semantics

Failure classes (`CoreFailure` sealed hierarchy, all implement `ItemFailure`):

- `Fatal`
- `Retryable`
- `Filtered`
- `InfrastructureFailure`

Rules:

- `Filtered` ends item progression without marking the run as failed
- `Retryable` schedules another attempt according to policy; `retryAtMs` is set on the item
- `Fatal` makes the item terminal failed; `lastErrorJson` is written
- infrastructure failures are runtime/store/adapter failures and are handled outside business `Raise<ItemFailure>`

Retry policy (`RetryPolicy`) defines:

- max attempts (`maxAttempts`)
- backoff delay between attempts (`backoffMs`); timeout is deferred to a future version

---

## Leasing And Recovery

Required runtime invariants:

- claimed work must have lease owner and lease expiry
- workers may only mutate items they currently lease
- expired leases are reclaimable via `reclaimExpiredLeases`
- `resumeRuns` must call `reclaimExpiredLeases` before re-executing any active run
- resume after restart depends only on store state
- in-memory queues may be rebuilt at startup without losing correctness

---

## Idempotency Rules

- one logical item is uniquely identified by `runId + itemKey`
- source duplicates within a run must not create duplicate logical work
- external steps should receive an idempotency key when the downstream system supports it

---

## Durable Append Rule

The ingestion rule is fixed for v1:

1. source adapter fetches a batch of records
2. runtime persists the full batch via `appendIngress` (bulk, idempotent)
3. only after persistence succeeds does the adapter ack the batch
4. on persistence failure, records remain unacked or are nacked for retry according to adapter behavior

This rule is the baseline for AMQP integration.

---

## Acceptance For V1 Contracts

These contracts are acceptable only if they allow:

- an in-memory fake store and fake source to implement the same interfaces
- a restart-safe Postgres implementation later
- a loyalty example pipeline with continuous ingress, per-item work, and a sequential phase (`INFINITE` mode)
- the ingestion loop to pause automatically when `countNonTerminal >= maxInFlight` without any step-author involvement
- zero dependency from `pipekt.core` on kt framework or RabbitMQ classes
- zero requirement for a public physical-planning API in the MVP

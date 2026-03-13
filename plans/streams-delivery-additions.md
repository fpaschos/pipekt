# Streams Delivery Additions

## Summary

This document captures important design clarifications, known gaps, and required fixes that emerged from a detailed review of the in-memory implementation and from production workload analysis. These additions supplement `streams-delivery-phases.md` and must be applied to the relevant phases before moving to Postgres.

**Production workload context:** infinite streams, thousands of messages per day growing to millions, 3-5 concurrent pipelines per instance. All additions are evaluated against this workload.

**V1 is INFINITE-only.** Additions that applied only to `BOUNDED` mode (barrier, finalizer) are marked as dropped.

---

## Addition 1 — `StepFn` Must Be `suspend` (Critical, Fix Now)

### Problem

The current `StepFn` typealias in `core/Types.kt` is not `suspend`:

```kotlin
typealias StepFn<I, O, E> = context(Raise<E>, StepCtx)
(I) -> O
```

This means step functions cannot call any suspending API — no Ktor HTTP client, no coroutine-based DB access, no `delay`. All real loyalty steps (eligibility check, policy fetch, phase2 call) are suspend operations. The compiler will reject them with the current definition.

### Fix

Add `suspend` to the typealias:

```kotlin
typealias StepFn<I, O, E> = suspend context(Raise<E>, StepCtx)
(I) -> O
```

The `either { }` block in the runtime is already suspend-capable. No other change is needed.

### Phase

Fix immediately, before any real step implementation is attempted. This is a prerequisite for Phase 5 (loyalty reference example).

---

## Addition 2 — Retry Backoff Is Not Applied (Bug)

### Problem

`RetryPolicy.backoffMs` exists as a field but is never used in `InMemoryRuntime.executeStep`. All retry attempts run back-to-back with zero delay between them. For a transient downstream failure (e.g. a briefly unavailable REST API), all `maxAttempts` exhaust instantly against the same failure condition.

### Fix

Apply `delay(backoffMs)` between attempts:

```kotlin
for (attempt in 1..step.retryPolicy.maxAttempts) {
    val result = either { step.fn(payload) }
    when {
        result.isRight() -> { ...; break }
        else -> {
            if (!isFinal && step.retryPolicy.backoffMs > 0) {
                delay(step.retryPolicy.backoffMs)
            }
        }
    }
}
```

### Phase

Phase 2 (Runtime and Backpressure).

---

## Addition 3 — `IN_PROGRESS` Items Are Stuck After a Crash (Bug)

### Problem

When a worker crashes while executing a step (after `claim`, before `checkpointSuccess`/`checkpointFailure`), the item is left at `status = IN_PROGRESS`. Worker loops only claim `PENDING` items, so these stuck items are invisible to `resumeRuns()` and are never retried. They remain stuck indefinitely.

### Fix

`reclaimExpiredLeases` must be called:
1. during `resumeRuns()` before re-executing any active run
2. by a watchdog loop that runs periodically during normal execution

```kotlin
suspend fun reclaimExpiredLeases(nowEpochMs: Long, limit: Int): List<WorkItem>
```

In Postgres this is a single atomic statement:

```sql
UPDATE work_items
SET status = 'PENDING', lease_owner = NULL, lease_expiry_ms = NULL
WHERE status = 'IN_PROGRESS' AND lease_expiry_ms < $now
RETURNING *
```

The in-memory implementation returns `emptyList()` (no lease expiry concept in-process). The real fix takes effect in Phase 3 (Postgres store).

### Phase

Interface addition in Phase 1. Real implementation in Phase 3. `resumeRuns` fix and watchdog loop in Phase 2.

---

## Addition 4 — Finalizer Lock Durability — DROPPED

**Status: Dropped. `BOUNDED` mode and the finalizer are not part of v1.**

This addition described making `tryStartFinalizer` durable via a Postgres upsert into a `finalizer_locks` table. Since `BOUNDED` pipelines and finalizers are deferred to a future version, this addition no longer applies.

See `future/streams-future-planning-features.md` for the deferred `BOUNDED` mode design notes.

---

## Addition 5 — Store Operations Must Be Atomic (Architecture)

### Problem

The current `DurableStore` interface exposes split operations that the runtime calls sequentially. Several pairs are not atomic and produce inconsistent state on crash between them:

| Operation pair | Risk if crash between them |
| --- | --- |
| `recordAttempt()` + `checkpointItem()` | Attempt recorded as SUCCESS but item stays IN_PROGRESS — item reclaimed and re-executed |
| `recordAttempt()` + `failItem()` | Attempt recorded as FATAL but item stays IN_PROGRESS — item reclaimed past exhausted retries |
| `createRun()` + `appendIngress() ×N` | Run exists with no work items — run loops forever doing nothing |
| `claimPendingItems()` SELECT + UPDATE | Two workers claim the same item — duplicate execution |

### Fix

Replace all split operations with atomic merged operations. The corrected `DurableStore` interface is:

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

  // Atomic checkpoints — each is a single transaction
  suspend fun checkpointSuccess(item: WorkItem, outputJson: String, nextStep: String?, nowMs: Long)
  suspend fun checkpointFiltered(item: WorkItem, reason: String, nowMs: Long)
  suspend fun checkpointFailure(item: WorkItem, errorJson: String, retryAtEpochMs: Long?, nowMs: Long)

  // Watchdog
  suspend fun reclaimExpiredLeases(nowEpochMs: Long, limit: Int): List<WorkItem>
}
```

Each `checkpointSuccess`, `checkpointFiltered`, and `checkpointFailure` executes inside a single Postgres transaction:
- increments `attempt_count`
- sets `last_error_json` if failure
- updates `current_step` and `status`
- nulls `payload_json` for terminal items
- all in one `UPDATE work_items SET ... WHERE id = $id`

For `claim`, the Postgres implementation must use:

```sql
SELECT * FROM work_items
WHERE current_step = $step AND run_id = $run_id AND status = 'PENDING'
LIMIT $limit
FOR UPDATE SKIP LOCKED
```

followed by the `UPDATE` to `IN_PROGRESS` within the same transaction.

For `appendIngress`, use bulk insert with conflict handling:

```sql
INSERT INTO work_items (run_id, source_id, ...)
VALUES ($1, $2, ...), ($3, $4, ...), ...
ON CONFLICT (run_id, source_id) DO NOTHING
```

### Phase

Interface shape fixed in Phase 1. Postgres transaction mechanics implemented in Phase 3.

---

## Addition 6 — Payload Storage And Compaction (Architecture Decision)

### Problem

`WorkItem.payloadJson` holds the full serialized payload of the item at its current step. After an enrichment step, this may be a large nested JSON document. At production scale (millions of items, potentially large payloads per item), retaining payloads in terminal items creates unbounded storage growth.

### Key Behaviour

`payloadJson` is **overwritten at each checkpoint** — it always holds the output of the most recently completed step. It is not a history.

### Compaction Rule (Engine Invariant)

`payload_json` must be nulled **immediately** inside the atomic checkpoint transaction when an item reaches a terminal state:

- `checkpointSuccess` with `nextStep = null` → null `payload_json`
- `checkpointFiltered` → null `payload_json`
- `checkpointFailure` (fatal, no more retries) → null `payload_json`, set `last_error_json`

This is not optional. It is an engine invariant enforced by every store implementation.

For non-terminal checkpoints (item advancing to the next step), `payload_json` is updated with the new step's output as before.

### Phase

Apply when designing the Postgres schema in Phase 3. The in-memory store should also implement this rule for consistency.

---

## Addition 7 — Ingestion/Execution Separation And Infinite Stream Lifecycle (Architecture)

### Problem

The current `PipelineRuntime` API couples ingestion and execution:

- `ingestAndExecute(runId, record)` takes a single record and runs the full pipeline for it synchronously
- there is no bulk ingestion path
- there is no separation between the AMQP ack boundary (after durable append) and the execution loop

For an infinite stream with 3-5 concurrent pipelines, this design means:
- 100 records from `sourceAdapter.poll(100)` require 100 sequential calls, each doing a DB write and a full execution pass
- AMQP ack is delayed until execution completes, which can take seconds per item
- the runtime has no watchdog for stuck `IN_PROGRESS` items

### Fix

The runtime exposes three independent operations, each running as a separate coroutine:

**Ingestion loop** (owned by the application or framework layer):
```kotlin
scope.launch {
    while (isActive) {
        val records = sourceAdapter.poll(maxItems = 100)
        if (records.isEmpty()) { delay(pollIntervalMs); continue }
        store.appendIngress(runId, records, clock.nowMs())  // bulk, idempotent
        sourceAdapter.ack(records)                          // ack only after durable append
    }
}
```

**Per-step worker loop** (one per step, owned by the runtime):
```kotlin
scope.launch {
    while (isActive) {
        val items = store.claim(step.name, runId, limit = 10, leaseMs = 30_000, workerId = workerId)
        if (items.isEmpty()) { delay(workerPollIntervalMs); continue }
        for (item in items) {
            executeAndCheckpoint(item, step)
        }
    }
}
```

**Watchdog loop** (owned by the runtime):
```kotlin
scope.launch {
    while (isActive) {
        delay(watchdogIntervalMs)
        store.reclaimExpiredLeases(clock.nowMs(), limit = 100)
    }
}
```

`resumeRuns()` on restart must call `reclaimExpiredLeases` once before launching worker loops.

### For INFINITE Pipelines

- the long-lived `runId` is obtained or created once at startup via `getOrCreateRun`
- no barrier or finalizer — items flow through all steps and reach `COMPLETED` or `FAILED`
- the run never transitions to a terminal state; the ingestion loop runs indefinitely
- `payload_json` is nulled at each terminal checkpoint (see Addition 6)

### Phase

Ingestion/execution separation and watchdog loop in Phase 2. Framework lifecycle integration in Phase 6.

---

## Addition 8 — Type Chain Validation Requires `suspend` Awareness

### Problem

The `KType` fields being added to `StepDef` and `FilterDef` in Phase 1 (contract alignment) validate that adjacent operators have compatible types. This validation must also account for the fact that after Addition 1 (`StepFn` becomes `suspend`), the `KType` of the function type changes. Type capture via `inline reified` at DSL call sites remains the correct approach — `typeOf<I>()` and `typeOf<O>()` capture the payload types, not the function type, so `suspend` on `StepFn` does not affect the captured `KType` values.

### Phase

Confirm during Phase 1 implementation. No additional work required beyond Addition 1.

---

## Addition 9 — Idempotency Key Contract for External Side Effects

### Summary

The engine guarantees that a checkpointed-success step is never re-executed for the same item. It does not guarantee that an external side effect inside a step is not duplicated on retry (the step may have partially executed before raising).

The contract for step authors:

- Use `stepCtx.itemId` (and `stepCtx.runId + stepCtx.itemKey`) as the idempotency key when calling external systems that support deduplication.
- For systems that do not support idempotency keys, accept at-least-once delivery and design consumers to tolerate duplicates.
- Avoid publishing to external systems before all computation in the step is complete. If unavoidable, split into two steps: one that computes and checkpoints, one that publishes.

This contract must be documented in the loyalty reference example (Phase 5) with a concrete example using `stepCtx.itemId`.

### Phase

Document in Phase 5 loyalty example. No engine change required.

---

## Addition 10 — Infinite Stream Storage Growth (Architecture)

### Problem

With infinite streams, `work_items` accumulates rows indefinitely. There is no run finalization event that naturally triggers cleanup. At thousands of messages per day, the table reaches hundreds of thousands of rows per year. Without archival, claim query performance degrades over time even with partial indexes.

### Fix

Two mechanisms:

**1. Payload nullification at terminal checkpoint (immediate)**

Handled by Addition 6 — `payload_json` is nulled inside every terminal checkpoint transaction. This eliminates payload storage for completed items immediately without any background job.

**2. Row archival/deletion (scheduled background job)**

A background job (daily or configurable) deletes or archives rows from `work_items` for terminal items older than N days:

```sql
DELETE FROM work_items
WHERE status IN ('COMPLETED', 'FILTERED', 'FAILED')
AND updated_at < $cutoff_ms
AND run_id IN (
  SELECT id FROM runs WHERE pipeline = $pipeline
)
```

For `INFINITE` pipelines the `runs` row is never terminal, so the job targets `work_items` directly by `updated_at`. The `runs` row for an INFINITE pipeline is never archived — it is the long-lived anchor for the pipeline instance.

The archival cutoff is **configurable per pipeline** via `retentionDays: Int` on `PipelineDefinition` (default 30). The archival job reads this value for each pipeline individually.

**Multi-pipeline row count math:** with multiple pipelines per server, row counts multiply. Example: 3 pipelines × 1M messages/day × 7 days = ~21M rows steady state; 3 pipelines × 1M/day × 30 days = ~90M rows. At 90M rows performance remains acceptable with the partial index, but `retentionDays` should be set lower (7–14 days) for high-volume pipelines to stay comfortably below this range.

### Phase

Phase 3 design decision. Document the archival job design as part of Phase 3 scope. Implement the job in Phase 6 (framework integration, where lifecycle scheduling is available).

---

## Addition 11 — `attempts` Table Removed From MVP (Architecture)

### Problem

The original design included a separate `attempts` table that records every execution attempt for every work item. At production scale this table:

- grows 3-5x faster than `work_items` due to retries
- is not required for engine correctness (retry scheduling needs only a counter; failure reporting needs only the last error)
- has no archival strategy defined
- was justified only by future observability needs (retry timeline UI, per-step latency tracking) that are explicitly deferred

### Fix

Remove the `attempts` table from the MVP schema. Replace with two fields on `work_items`:

- `attempt_count INT NOT NULL DEFAULT 0` — incremented on every checkpoint call; used for retry scheduling
- `last_error_json TEXT` — set on `FAILED` terminal checkpoint; null otherwise

The engine never needs to read the full attempt history to make execution decisions.

The current `AttemptRecord` entity, `recordAttempt`, and `getAttempts` in the `DurableStore` interface must be removed.

### Future

The full `attempts` table is the correct solution when observability tooling is built (retry timeline UI, per-step latency dashboards, replay tooling). It should be added at that time, not before. See `future/streams-future-planning-features.md` Section 7.

### Phase

Phase 1 — remove `AttemptRecord`, `recordAttempt`, `getAttempts` from the store SPI and entities immediately.

---

## Postgres Schema (Phase 3 Reference)

```sql
CREATE TABLE runs (
    id           TEXT PRIMARY KEY,
    pipeline     TEXT NOT NULL,
    plan_version TEXT NOT NULL,
    status       TEXT NOT NULL,
    created_at   BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL
);

-- Fast lookup of active runs on restart
CREATE INDEX idx_runs_active
    ON runs (pipeline, status)
    WHERE status NOT IN ('FAILED');

CREATE TABLE work_items (
    id              TEXT PRIMARY KEY,
    run_id          TEXT NOT NULL REFERENCES runs(id),
    source_id       TEXT NOT NULL,
    current_step    TEXT NOT NULL,
    status          TEXT NOT NULL,
    payload_json    TEXT,               -- nullable; nulled at terminal checkpoint
    last_error_json TEXT,               -- set on FAILED terminal, null otherwise
    attempt_count   INT NOT NULL DEFAULT 0,
    lease_owner     TEXT,
    lease_expiry_ms BIGINT,
    retry_at_ms     BIGINT,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    UNIQUE (run_id, source_id)
);

-- Supports claim query and reclaimExpiredLeases; partial index excludes terminal rows
CREATE INDEX idx_work_items_claim
    ON work_items (current_step, status, lease_expiry_ms)
    WHERE status IN ('PENDING', 'IN_PROGRESS');
```

No `attempts` table in MVP. No `finalizer_locks` table. Background archival/deletion job targets `work_items` rows older than N days (configurable, minimum 30 days recommended). The `runs` row for an INFINITE pipeline is permanent.

---

---

## Addition 12 — True Backpressure Via `maxInFlight` (Architecture)

### Problem

The ingestion loop as specified in Addition 7 has no mechanism to slow down when downstream steps are falling behind. If step-2 is failing or slow, `work_items` rows accumulate unboundedly at that step. The ingestion loop continues polling and appending at full speed, creating unbounded store growth and masking the real problem.

This is a critical gap for production workloads. Without backpressure, a slow or failing step causes the store to grow without limit, eventually degrading claim query performance and making the operational problem invisible until it becomes a storage crisis.

### Fix

Add a single `maxInFlight` parameter to the pipeline definition. The ingestion loop checks the total count of non-terminal items before every poll and pauses when the threshold is exceeded.

**`PipelineDefinition`** gains:

```kotlin
val maxInFlight: Int  // e.g. 500
```

**`DurableStore`** gains:

```kotlin
// Count of PENDING + IN_PROGRESS items for this run — used for backpressure
suspend fun countNonTerminal(runId: String): Int
```

**Ingestion loop** (updated from Addition 7):

```kotlin
scope.launch {
    while (isActive) {
        val inFlight = store.countNonTerminal(runId)
        if (inFlight >= maxInFlight) {
            delay(backpressureCheckIntervalMs)
            continue  // do not poll, do not append, do not ack
        }
        val records = sourceAdapter.poll(maxItems = 100)
        if (records.isEmpty()) { delay(pollIntervalMs); continue }
        store.appendIngress(runId, records, clock.nowMs())
        sourceAdapter.ack(records)
    }
}
```

The `maxInFlight` value is the equivalent of a bounded buffer capacity in in-memory reactive stream engines (Akka Streams, Reactor). When the buffer is full, the producer stops. Here the store is the buffer and `countNonTerminal` is the fill level.

### Rules

- `maxInFlight` is declared at pipeline definition time by the user; it is not auto-tuned
- a reasonable default is `workerConcurrency × stepCount × 2` but the user should set it explicitly
- the engine enforces backpressure internally; step authors never interact with it
- the in-memory fake store must implement `countNonTerminal` accurately for Phase 2 tests to be meaningful
- `countNonTerminal` in Postgres: `SELECT COUNT(*) FROM work_items WHERE run_id = $run_id AND status IN ('PENDING', 'IN_PROGRESS')`

### Phase

Phase 2 (Runtime and Backpressure). `countNonTerminal` interface stub in Phase 1.

---

## Addition 13 — Adapter-Level Pre-Filter For High-Discard Flows (Design Note)

### Problem

In flows where a large fraction of messages are discarded by business logic (e.g. 90% of 1k messages per batch), the pipeline `filter` operator still causes every message to be:

1. written to `work_items` via `appendIngress`
2. leased and claimed by the filter worker
3. executed through the filter predicate
4. checkpointed as `FILTERED` with `payload_json` nulled

This is correct behavior but high write churn for items that could be excluded before they enter the store at all.

### Options

**Option A — Adapter-level pre-filter (before `appendIngress`)**

Apply a cheap synchronous predicate in the source adapter before calling `appendIngress`. Items that don't pass are nacked or silently discarded at the adapter level. They never enter `work_items`.

- zero store cost for discarded items
- no `FILTERED` rows, no lease churn
- no audit trail — there is no record that these messages arrived
- predicate must be stateless and have no IO

**Option B — Pipeline `filter` operator (current behavior)**

Keep the filter inside the pipeline as a standard `filter` operator. Items are stored, claimed, and checkpointed as `FILTERED`.

- full audit trail: discarded items appear in `work_items` as `FILTERED`
- `StepCtx` is available in the predicate (access to `itemId`, `runId`, retry state)
- correct choice when the filter requires business logic, context, or auditability

### Rule

Use adapter pre-filter for **mechanical exclusions** that are purely structural:

- wrong message type or schema version
- wrong routing key or topic
- messages outside a version range

Use pipeline `filter` for **business-logic exclusions** that need:

- `StepCtx` fields
- audit trail of what arrived and was excluded
- retry semantics (filter predicate can fail and retry)

### Constraint

An adapter pre-filter predicate must be:

- stateless
- free of IO and suspend calls
- cheap enough to run on every message in the poll batch

### No Engine Change Required

This is a design pattern, not an engine feature. The `SourceAdapter` contract already supports it — the adapter calls `appendIngress` only for the records it decides to ingest. No new operator, no new store method, no new contract.

### Phase

Design note. Document in Phase 4 (AMQP adapter) and Phase 5 (loyalty reference example) as a guidance pattern for adapter implementors.

---

## Addition 14 — `PayloadSerializer` Abstraction (Architecture)

### Problem

Hardcoding `kotlinx.serialization` inside the engine couples every user of the library to it and prevents KMP targets from using alternative serialization formats (e.g. protobuf, CBOR). The engine writes and reads `payloadJson` at every checkpoint — this is the natural serialization boundary.

### Fix

Define a `PayloadSerializer` interface in `streams.core`:

```kotlin
interface PayloadSerializer {
    fun <T> serialize(value: T, type: KType): String
    fun <T> deserialize(json: String, type: KType): T
}
```

The engine holds a `PayloadSerializer` reference and calls it when writing or reading `payloadJson`. The default implementation uses `kotlinx.serialization`. Users on KMP targets or with format requirements may supply an alternative.

`KType` is already captured at DSL call sites via `inline reified` for type-chain validation — serialization is a natural extension of the same mechanism. Step authors never interact with `PayloadSerializer` directly.

### Phase

Phase 1 — interface in `streams.core` alongside the other core contracts.

---

## Addition 15 — Graceful Shutdown (Operations)

### Problem

Without graceful shutdown, every process restart leaves `IN_PROGRESS` items for the watchdog to reclaim on the next startup. The watchdog reclaim path is correct but adds latency before those items can be re-executed. With dozens or hundreds of in-flight items at shutdown time, this creates a burst of reclaim work on every restart.

Graceful shutdown makes clean stop the common case and reduces watchdog reclaim to a recovery mechanism for crashes only.

### Fix

On SIGTERM (or equivalent shutdown signal), the runtime must:

1. Stop the ingestion loop immediately — no new items are appended
2. Let all worker loops drain: each worker completes its current `executeAndCheckpoint` call and does not claim new items
3. Stop the watchdog loop after workers have drained
4. Signal process exit only after all loops have stopped

The kt-framework lifecycle (Phase 6) owns the SIGTERM hook and calls the runtime shutdown in this order. The runtime exposes a `suspend fun shutdown()` method that performs the drain and returns when all loops are stopped.

### Phase

Phase 6 (kt-framework integration, where lifecycle hooks are available).

---

## Summary Table

| Addition | Severity | Phase |
| --- | --- | --- |
| 1. `StepFn` missing `suspend` | Critical | Fix now |
| 2. Retry backoff not applied | Bug | Phase 2 |
| 3. `IN_PROGRESS` items stuck after crash | Bug | Phase 1 (interface) / Phase 2 (watchdog) / Phase 3 (impl) |
| 4. Finalizer lock not durable | Dropped | BOUNDED mode removed from v1 |
| 5. Store operations not atomic | Architecture | Phase 1 (interface) / Phase 3 (impl) |
| 6. Payload compaction at terminal checkpoint | Architecture | Phase 3 |
| 7. Ingestion/execution separation and infinite lifecycle | Architecture | Phase 2 / Phase 6 |
| 8. Type chain + `suspend` awareness | Confirmation | Phase 1 |
| 9. Idempotency key contract | Documentation | Phase 5 |
| 10. Infinite stream storage growth | Architecture | Phase 3 (design) / Phase 6 (archival job) |
| 11. `attempts` table removed from MVP | Architecture | Phase 1 (remove from SPI) |
| 12. True backpressure via `maxInFlight` | Architecture | Phase 1 (interface stub) / Phase 2 (implementation) |
| 13. Adapter-level pre-filter for high-discard flows | Design Note | Phase 4 / Phase 5 (guidance) |
| 14. `PayloadSerializer` abstraction | Architecture | Phase 1 (interface in `streams.core`) |
| 15. Graceful shutdown | Operations | Phase 6 |

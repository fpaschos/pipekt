# Streams Delivery Phases

## Implementation Status

### Phase 1A — DSL + Validation (complete)
- [x] core types (StepCtx, StepFn, RetryPolicy, FilteredReason, ItemFailure)
- [x] operator definitions (SourceDef, StepDef, FilterDef)
- [x] SourceAdapter contract
- [x] PayloadSerializer interface + KotlinxPayloadSerializer
- [x] PipelineDefinition + validate() + DSL builder (build() returns Either; errors: DuplicateStepName, NoSourceDefined, EmptyPipeline, InvalidMaxInFlight, TypeMismatch)
- [x] PipelineDefinitionTest + PipelineOperatorsTest passing

### Phase 1B — Store SPI + Entities (complete)
- [x] RunRecord, WorkItem, AppendIngressResult in `pipekt.store`
- [x] DurableStore interface with getOrCreateRun, getRun, listActiveRuns, appendIngress, claim, checkpointSuccess/Filtered/Failure, countNonTerminal, reclaimExpiredLeases
- [x] WorkItemStatus remains in core; store depends on core for IngressRecord and WorkItemStatus
- [x] Removed AppendIngressResult enum from core in favor of store's bulk result type

### Phase 1C — InMemoryStore + FakeSourceAdapter (complete)

### Phase 1D — PipelineRuntime + happy-path tests (complete)

---

## Summary

This document turns the `streams` architecture into an implementation sequence. The order is designed to validate contracts early, keep the loyalty example anchored to generic APIs, and postpone kt-framework integration until the core library is proven.

The production workload is: **infinite streams, thousands of messages per day growing to millions, 3-5 concurrent pipelines per instance**. All phases must be evaluated against this workload.

V1 supports only `INFINITE` pipelines. `BOUNDED` mode is explicitly deferred.

## Phase 1: In-Memory Core

### Goal

Create the minimum runnable `streams` engine with no external infrastructure dependencies.

### Scope

- define `pipekt.core` contracts
- define `pipekt.store` SPI with atomic checkpoint operations
- `WorkItem` entity must carry: `leaseOwner`, `leaseExpiryMs`, `retryAtMs`, `attemptCount`, `lastErrorJson`, nullable `payloadJson`
- `StepFn` must be `suspend`
- `StepCtx` must carry: `pipelineName`, `runId`, `itemId`, `itemKey`, `stepName`, `attempt`, `startedAt`, `metadata`
- `PipelineDefinition` must carry `retentionDays: Int` (default 30) — archival job reads this per-pipeline cutoff
- define `PayloadSerializer` interface in `pipekt.core` — engine-level serialization boundary; default implementation uses `kotlinx.serialization`; swappable for KMP targets
- build an in-memory fake store
- build a fake source adapter
- validate pipeline definition and operator ordering
- `reclaimExpiredLeases` stub on `DurableStore` (returns `emptyList()` in-memory; real implementation in Phase 3)
- `countNonTerminal` stub on `DurableStore` (returns count of `PENDING` + `IN_PROGRESS` items for the run; used by ingestion loop backpressure in Phase 2)

### Entry Criteria

- `streams-contracts-v1.md` is accepted as the source of truth
- package layout under `...loyalty.streams` is fixed

### Exit Criteria

- a pipeline can be defined and validated
- an in-memory runtime can ingest fake records and move them across steps
- `WorkItem` entity carries all required fields including lease and attempt tracking fields
- `StepFn` is `suspend`
- `DurableStore` interface matches the v1 contracts exactly (atomic checkpoints, bulk `appendIngress`, `claim` with lease params, `reclaimExpiredLeases`, `countNonTerminal`)
- no `AttemptRecord` / `attempts` table in the store SPI or entities
- `PipelineDefinition` carries `retentionDays: Int` (default 30)
- `PayloadSerializer` interface exists in `pipekt.core` with a default `kotlinx.serialization` implementation

### Deferred

- Postgres
- RabbitMQ
- kt framework
- production observability

---

## Phase 2: Runtime And Backpressure

### Goal

Add real execution discipline to the in-memory engine, including the separation of ingestion from execution.

### Scope

- separate ingestion loop from per-step worker loops
- ingestion loop pattern: `countNonTerminal check` → `sourceAdapter.poll(maxItems)` → `bulk appendIngress` → `sourceAdapter.ack()`
- backpressure: ingestion loop pauses when `store.countNonTerminal(runId) >= pipeline.maxInFlight`; resumes when it drops below the threshold
- per-step worker loops: `claim(step, runId, limit, leaseMs, workerId)` → execute `StepFn` → atomic checkpoint
- watchdog loop: periodic `reclaimExpiredLeases` call
- per-step concurrency limits
- retry policy execution with backoff delay between attempts
- lease ownership and reclaim behavior in runtime logic
- `resumeRuns` must call `reclaimExpiredLeases` before re-executing active runs

### Entry Criteria

- in-memory pipeline executes basic happy-path flows
- pipeline definition validation is stable
- `countNonTerminal` stub exists on `DurableStore`

### Exit Criteria

- ingestion loop and worker loops are independent coroutines
- ingestion loop pauses when `countNonTerminal >= maxInFlight` and resumes when it drops below
- a test must demonstrate that ingestion stops appending when workers are artificially slowed and `maxInFlight` is reached
- sequential steps remain single-concurrency
- retries apply backoff delay between attempts
- lease expiry and reclaim are testable in-memory
- watchdog loop resets stuck `IN_PROGRESS` items in tests

### Deferred

- durable SQL persistence
- AMQP adapter
- kt framework composition

---

## Phase 3: Durable Postgres Store

### Goal

Replace the fake store with a durable implementation that becomes the source of truth for recovery. The schema must be designed for production-scale infinite stream workloads.

### Scope

- Postgres schema for runs and work items (no `attempts` table in MVP)
- atomic checkpoint queries: each `checkpointSuccess`, `checkpointFiltered`, `checkpointFailure` is a single transaction
- `payload_json` nulled at terminal checkpoint inside the checkpoint transaction
- `claim` using `SELECT ... FOR UPDATE SKIP LOCKED` within a single transaction
- `appendIngress` bulk insert with `ON CONFLICT (run_id, source_id) DO NOTHING`
- `reclaimExpiredLeases`: atomic `UPDATE work_items SET status = 'PENDING' WHERE status = 'IN_PROGRESS' AND lease_expiry_ms < $now`
- restart and reclaim behavior
- rolling-run strategy for INFINITE pipelines: document how the long-lived `runId` is created and maintained across restarts
- background archival/deletion job design: terminal `work_items` rows deleted or archived after N days (configurable); `runs` rows archived after all their items are archived

### Postgres Schema

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
    last_error_json TEXT,               -- set on FAILED, null otherwise
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

No `attempts` table in MVP. No `finalizer_locks` table. Background archival/deletion job targets `work_items` rows older than `retentionDays` days (read from `PipelineDefinition`; default 30; set lower for high-volume pipelines). The `runs` row for an INFINITE pipeline is permanent.

**Run status semantics:** `status` is a coarse run lifecycle and health indicator. For `INFINITE` pipelines the expected values are:

- `ACTIVE` — run is healthy and available for ingestion and execution. An `INFINITE` run typically remains `ACTIVE` for its entire lifetime.
- `FAILED` — run entered an unrecoverable failure state; no further ingestion or execution should occur for this run.

`idx_runs_active` uses `WHERE status NOT IN ('FAILED')` to quickly locate restartable runs on startup. Only `FAILED` is treated as non-active in v1; `INFINITE` runs have no `COMPLETED` status.

**Rolling-run strategy:** `DurableStore.getOrCreateRun(pipeline, planVersion, nowMs)` is keyed by `(pipeline, planVersion)`. On restart with the same plan version the existing `ACTIVE` run is resumed. When a pipeline change is incompatible with existing work items (e.g. payload schema change, topology rewrite), `planVersion` must be bumped. This creates a new `RunRecord` with a fresh `id` while the older run remains in the `runs` table (usually transitioning to `FAILED` or left as a historical record). Tooling that decides which run to resume must use the `(pipeline, planVersion)` pair together with `status` from `listActiveRuns`.

### Implementation Notes (sqlx4k)

The Postgres store implementation uses [sqlx4k](https://github.com/smyrgeorge/sqlx4k) as the database driver. The following decisions apply:

- **Migrations**: use `db.migrate(path, table)` built into sqlx4k — no Flyway dependency. Ship the schema as a SQL migration file; users include it in their existing migration path.
- **Atomic checkpoints**: each `checkpointSuccess`, `checkpointFiltered`, `checkpointFailure` runs inside `db.transaction {}`. The transaction increments `attempt_count`, updates `status`, and nulls `payload_json` for terminal items — all in one `UPDATE`.
- **`claim`**: implemented as a raw `Statement` with `SELECT ... FOR UPDATE SKIP LOCKED` executed inside a `db.transaction {}`. The `UPDATE` to `IN_PROGRESS` follows in the same transaction.
- **`appendIngress`**: hand-written multi-row `VALUES` string (dynamic row count); `ON CONFLICT (run_id, source_id) DO NOTHING`. sqlx4k codegen batch insert is not used here.
- **Codegen**: use sqlx4k `@Repository` codegen only for simple reads (`getRun`, `listActiveRuns`). All checkpoint, claim, and reclaim queries are hand-written `Statement` calls.
- **`payload_json` column type**: `TEXT` for MVP. `JSONB` is a deferred upgrade — one-word DDL change; no query benefit since payload is opaque to the store engine.
- **Archival cutoff**: the archival job reads `retentionDays` from `PipelineDefinition` for each pipeline and deletes `work_items` rows where `updated_at < now - retentionDays`.

### Entry Criteria

- runtime semantics are stable in the fake environment
- store contract has no open design questions
- rolling-run strategy for INFINITE pipelines is documented

### Exit Criteria

- the engine can stop and resume from store state
- duplicate ingress for the same `runId + itemKey` is rejected or coalesced correctly
- expired leases are reclaimable
- terminal items have `payload_json = NULL` after checkpoint
- background archival job design is documented and tested against a simulated 24h infinite stream
- INFINITE pipeline can run for 24h without unbounded table growth

### Deferred

- AMQP integration
- kt framework integration

---

## Phase 4: AMQP Source Adapter

### Goal

Connect RabbitMQ to the generic source contract without changing core engine contracts.

### Scope

- implement `pipekt.adapters.amqp`
- map RabbitMQ messages to `SourceRecord<T>`
- enforce durable append before ack (bulk: poll batch → `appendIngress(batch)` → ack batch)
- carry broker metadata only at adapter level
- port or re-derive useful transport behavior from legacy `ampq`

### Entry Criteria

- runtime and Postgres store are functional without RabbitMQ

### Exit Criteria

- AMQP messages can feed the generic engine via bulk ingestion
- ack/nack behavior matches durable append rules
- no core contract depends on RabbitMQ classes

### Deferred

- production bootstrap with kt framework
- transport generalization beyond AMQP

---

## Phase 5: Loyalty Reference Example

### Goal

Validate that the generic engine supports the intended loyalty workflow end to end as a continuous INFINITE pipeline.

### Scope

- loyalty domain types
- loyalty step implementations
- INFINITE reference pipeline: `filter → step → step` (continuous ingress, no barrier, no finalizer; sequential phase is a second step — concurrency limits are a runtime concern, not in the DSL in v1)
- acceptance tests for restart, retries, sequential phase, and reclaim
- acceptance tests for continuous ingress and per-item completion
- verify `payload_json` is nulled on terminal checkpoint

### Entry Criteria

- AMQP adapter can feed the engine
- durable store and runtime semantics are stable

### Exit Criteria

- the loyalty flow runs on generic operators only
- no loyalty-specific logic is added to core runtime
- acceptance tests cover the intended business path and failure path

### Deferred

- replacement of the current application wiring
- kt framework integration

---

## Phase 6: KT Framework Integration

### Goal

Integrate the validated `streams` library into the actual service composition model.

### Scope

- **pipeline registry/manager** (`PipelineManager`): a central component that owns one `PipelineRuntime` per registered `PipelineDefinition`, providing `startPipeline`, `stopPipeline`, `listPipelines`, and `getRuntime` operations. Specified in `streams-technical-requirements.md`; implemented in this layer, not in `pipekt` commonMain.
- lifecycle ownership for 3-5 concurrent pipelines per service instance
- DI wiring
- tracing/context propagation bridges
- configuration loading
- startup/shutdown behavior
- coroutine scope management for ingestion, worker, and watchdog loops
- background archival job scheduling
- **graceful shutdown**: on SIGTERM, stop the ingestion loop first, let worker loops drain all currently claimed items to checkpoint, then stop the watchdog; avoids leaving `IN_PROGRESS` items on every restart
- **Micrometer metrics registration** — expose per-pipeline gauges and counters:
  - `streams.in_flight{pipeline}` — current `countNonTerminal` value
  - `streams.backpressure.active{pipeline}` — 0/1 gauge; 1 when ingestion is paused
  - `streams.items.completed{pipeline,step}` — counter
  - `streams.items.failed{pipeline,step}` — counter
  - `streams.items.filtered{pipeline}` — counter
  - `streams.step.duration{pipeline,step}` — histogram (time from claim to checkpoint)
- **health endpoint**: `GET /pipelines/{name}/health` — returns in-flight count, backpressure state (paused/running), worker loop lag, last successful checkpoint timestamp; registered via ktkit `AbstractRestHandler`
- **`pipekit tail` support**: emit Postgres `NOTIFY` on every terminal checkpoint event so the CLI can stream live pipeline activity via sqlx4k `LISTEN/NOTIFY`

### Entry Criteria

- loyalty example proves the engine shape
- no open questions remain about core contracts

### Exit Criteria

- the new library can be composed into the service cleanly
- framework integration remains a thin layer around the engine
- legacy `ampq` usage can be evaluated for replacement or removal
- graceful shutdown drains worker loops before process exit
- Micrometer metrics are registered and visible
- health endpoint responds correctly
- `NOTIFY` is emitted on terminal checkpoints

### Deferred

- broader productization and extraction into a separate shared module
- CLI (`pipekit`) — Phase 7

---

## Phase 7: CLI (`pipekit`)

### Goal

Provide a native binary CLI for operational and development access to running pipelines.

### Scope

- separate Gradle submodule `streams-cli`
- [clikt](https://github.com/ajalt/clikt) + Kotlin/Native, compiled to a native binary — no JVM runtime required to run the CLI
- Ktor client (native target) calls Phase 6 admin HTTP endpoints
- commands:
  - `pipekit status` — list all pipelines with in-flight counts and backpressure state
  - `pipekit inspect <itemId>` — show full item state: step, status, attempt count, last error
  - `pipekit retry <itemId>` — reset a `FAILED` item to `PENDING`
  - `pipekit retry --pipeline <name> --all-failed` — bulk retry all failed items for a pipeline
  - `pipekit tail --pipeline <name>` — stream live terminal checkpoint events via sqlx4k `LISTEN/NOTIFY`
  - `pipekit drain --pipeline <name>` — stop ingestion loop, wait for in-flight items to complete

### Entry Criteria

- Phase 6 admin HTTP endpoints and `NOTIFY` events are stable and tested

### Exit Criteria

- all commands work correctly against a running server
- native binary builds cleanly for target platform
- `pipekit tail` streams live events without polling

### Deferred

- pipeline graph visualizer (`pipekit graph`) — see `future/streams-future-planning-features.md` Section 7

---

## Cross-Phase Test Expectations

Tests must accumulate by phase rather than being postponed:

- phase 1:
  - pipeline validation
  - type-chain validation
  - `DurableStore` interface correctness (atomic checkpoints, bulk ingress, claim with lease params)
- phase 2:
  - backpressure: ingestion pauses when `countNonTerminal >= maxInFlight`
  - backpressure: ingestion resumes when workers drain items below threshold
  - retries with backoff
  - lease reclaim
  - sequential step enforcement
  - ingestion loop and worker loop independence
  - watchdog resets stuck items
- phase 3:
  - restart recovery
  - idempotent ingress
  - `payload_json` is NULL after terminal checkpoint
  - compaction does not affect restart correctness
  - infinite run reclaim and resumption after simulated crash
  - 24h infinite run does not cause unbounded table growth
- phase 4:
  - durable append before ack (bulk)
  - nack/retry behavior
- phase 5:
  - full INFINITE loyalty acceptance with fault injection
  - continuous ingress and per-item completion
- phase 6:
  - service bootstrap and lifecycle correctness for multiple concurrent pipelines

---

## Definition Of Ready For Coding

Implementation can start when:

- the five `streams` planning docs exist and agree with each other
- contracts are stable enough to implement phase 1 without new design work
- the loyalty flow is clearly separated from core engine concerns
- INFINITE-only pipeline mode is documented and `BOUNDED` is explicitly deferred
- kt-framework integration is explicitly postponed, not ambiguous

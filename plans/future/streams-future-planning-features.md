# Streams Future Planning Features

## Summary

This document captures planning ideas that are intentionally outside the current MVP for `io.github.fpaschos.pipekt`.

The purpose is to preserve useful future direction without contaminating the initial design with premature complexity.

---

## Not In The Current MVP

The current MVP does not include:

- `BOUNDED` pipeline mode (barrier, finalizer, `finalizer_locks`, `FINALIZED` run status)
- a public physical-plan API
- a rule-based optimizer
- a DataFusion-style planner stack
- multiple execution strategies for the same logical pipeline
- SQL or declarative query planning
- Arrow or columnar execution
- transport support beyond the first AMQP adapter
- advanced framework integration features
- a full `attempts` table (deferred — see Section 7)

---

## Future Feature Groups

## 0) BOUNDED Pipeline Mode

V1 supports only `INFINITE` pipelines. `BOUNDED` mode is deferred until a concrete batch-boundary use case exists in the production workload.

What `BOUNDED` mode would add:

- finite item set per run (batch ingress)
- run has a defined terminal state (`FINALIZED` or `FAILED`)
- `barrier` operator: waits for all items in a finite predecessor stage to reach a terminal state before allowing downstream operators to proceed
- `finalizeRun` operator: runs exactly once per run after the barrier is satisfied; guarded by durable ownership
- `AWAITING_BARRIER` and `FINALIZED` run status values
- `finalizer_locks` table: durable atomic upsert to prevent duplicate finalizer execution across restarts

```sql
CREATE TABLE finalizer_locks (
    run_id      TEXT PRIMARY KEY,
    started_at  BIGINT NOT NULL,
    finished_at BIGINT
);
```

- `startFinalizer(runId, nowMs)` → `FinalizerStartResult` (STARTED | ALREADY_STARTED) — atomic insert with `ON CONFLICT DO NOTHING`
- `completeFinalizer(runId, nowMs)`
- `evaluateBarrier(runId, predecessorStep)` → `BarrierResult` (READY | WAITING) — derived from durable item state

`DurableStore` additions for `BOUNDED`:

```kotlin
suspend fun evaluateBarrier(runId: String, predecessorStep: String): BarrierResult
suspend fun startFinalizer(runId: String, nowMs: Long): FinalizerStartResult
suspend fun completeFinalizer(runId: String, nowMs: Long)
```

`PipelineDefinition` validation additions for `BOUNDED`:

- reject `barrier` or `finalizeRun` in `INFINITE` pipelines
- reject multiple finalizers
- reject a barrier with no finite predecessor stage

Trigger to add `BOUNDED` mode: a real business requirement for batch-boundary processing where items must be grouped into a finite run and a run-level side effect must fire once after all items complete.

---

## 1) Planning And Optimization

Possible future additions:

- public `LogicalPlan` representation
- internal or public `PhysicalPlan` representation
- rule-based optimization passes
- plan validation and rewrite hooks
- printable plan trees for debugging

Potential value:

- cleaner extension points
- operator fusion opportunities
- filter pushdown before expensive remote calls
- easier reasoning about pipeline rewrites

Why deferred:

- current pain is execution correctness, not plan optimization
- durability, retries, and recovery matter more than plan sophistication in the MVP

---

## 2) Session And Registry Model

Possible future additions:

- `PipelineSession`
- adapter registry
- serializer registry
- reusable runtime configuration bundles

Potential value:

- cleaner multi-pipeline hosting
- standardized extension and registration model

Why deferred:

- a simple constructor-based setup is enough for the first implementation

---

## 3) Advanced Runtime Execution

Possible future additions:

- alternative executors for sequential vs parallel stages
- pluggable scheduling policies
- dynamic worker balancing
- throughput-aware throttling
- richer lease and heartbeat controls

Potential value:

- higher throughput and better tuning under different workloads

Why deferred:

- first implementation should optimize for correctness and observability, not execution strategy flexibility

---

## 4) Store Evolution

Possible future additions:

- multiple SQL dialects
- store-specific compaction or archival extensions
- plan version tracking in persisted records
- stronger exactly-once coordination patterns
- full `attempts` table as an opt-in observability store (see Section 7)

Note on the `attempts` table:

The MVP replaces the full `attempts` table with `attempt_count` and `last_error_json` fields on `work_items`. This is sufficient for engine correctness and retry scheduling. A full `attempts` table — recording every attempt with timing and outcome — is the correct solution for the observability phase. It is explicitly deferred, not permanently dropped. When the observability tooling is built (Section 7), the `attempts` table should be introduced as an additive store extension alongside the admin tooling that consumes it.

Potential value:

- better operability and portability
- full audit trail for retry investigation

Why deferred:

- Postgres-only support is enough to validate the engine model first
- the `attempts` table grows 3-5x faster than `work_items` and requires an archival strategy before it is safe to add at production scale

---

## 5) Adapter Expansion

Possible future additions:

- Kafka source adapter
- HTTP/webhook ingress adapter — someone POSTs an event; adapter appends it as an ingress record
- Scheduled/timer source — polls a DB or API on a configurable interval; emits records (e.g. "process all pending rewards for today")
- Postgres CDC / outbox source — reads from a transactional outbox table; useful for outbox pattern integrations
- CSV/file source — requires `BOUNDED` mode (Section 0); deferred until `BOUNDED` mode is implemented
- sink adapters with uniform delivery guarantees
- richer metadata propagation and tracing bridges

Potential value:

- broader reuse of the library outside loyalty

Why deferred:

- AMQP is the first real integration target and should validate the adapter SPI before more transports are added
- CSV/file source specifically requires `BOUNDED` mode to model finite runs correctly (engine cannot distinguish "file exhausted" from "temporarily idle source")

---

## 6) Framework Composition

Possible future additions:

- kt-framework lifecycle integration
- DI modules and auto-wiring helpers
- standardized config loading and binding
- tracing/context propagation bridges

Potential value:

- easier service integration after the engine stabilizes

Why deferred:

- framework coupling would distort the core engine contracts if introduced too early

---

## 7) Observability And Tooling

Possible future additions:

- full `attempts` table — records every step execution attempt with outcome, timing, and failure details
- plan visualization
- richer metrics and dashboards
- run inspection utilities
- replay tooling
- admin APIs for run control
- **pipeline graph visualizer**: `pipekit graph --pipeline <name>` — prints an ASCII diagram of the pipeline operator chain with current item counts per step; useful for debugging stuck pipelines

Note: the `pipekit` CLI itself (commands: `status`, `inspect`, `retry`, `tail`, `drain`) is a **delivery item** in Phase 7 of `streams-delivery-phases.md`, not a future feature. It is included here only for the graph visualizer command which is deferred beyond Phase 7.

The `attempts` table is the **prerequisite** for the full observability stack. Without it:

- retry timelines cannot be displayed ("this item failed 3 times, here's why each time")
- per-step latency cannot be tracked across items
- replay tooling cannot reconstruct the execution history of a specific item

When observability tooling is prioritized, add the `attempts` table at the same time:

```sql
CREATE TABLE attempts (
    id             TEXT PRIMARY KEY,
    work_item_id   TEXT NOT NULL REFERENCES work_items(id),
    run_id         TEXT NOT NULL,
    step_name      TEXT NOT NULL,
    attempt_number INT NOT NULL,
    outcome        TEXT NOT NULL,   -- SUCCESS, RETRYABLE_FAILURE, FATAL_FAILURE, FILTERED
    failure_json   TEXT,
    started_at     BIGINT NOT NULL,
    finished_at    BIGINT NOT NULL
);
```

The `attempts` table requires its own archival strategy (delete rows older than N days after the parent `work_item` is terminal and archived). Do not add the table without defining this strategy.

Potential value:

- operational clarity and easier debugging
- full audit trail for customer support

Why deferred:

- basic logs and counters are enough for MVP validation
- `attempt_count` and `last_error_json` on `work_items` satisfy engine correctness and first-level debugging

---

## Engine Positioning And Comparisons

### What This Engine Has That In-Memory Engines Do Not

**Akka Streams** and **Project Reactor** provide rich operator sets and true reactive backpressure but offer no durability. A crash loses all in-flight work. There is no concept of a work item that survives restart, no lease, and no per-item retry from a durable record.

This engine's differentiating properties over in-memory engines:

- durable item state persisted in Postgres
- restart safety from store state alone
- lease-based crash recovery via watchdog reclaim

### What This Engine Does Not Have (vs Kafka Streams / Flink)

**Kafka Streams** and **Apache Flink** support:

- DAG topologies (branch, merge, join, fanout)
- time-windowed aggregations
- stateful joins across streams
- exactly-once semantics via distributed checkpointing

These are not part of v1 and are explicitly deferred (see Section 3 — Advanced Runtime Execution, and the DAG operator discussion in operator expansion ideas). The loyalty use case requires a linear pipeline with durable item state — not DAG topology or windowed aggregation.

### Closest Conceptual Match: Temporal.io

The closest existing system in concept is **Temporal.io** — durable workflow execution with retries, leases, and step-level checkpointing. Key differences:

- this engine runs on Postgres; Temporal uses a proprietary persistence service
- this engine is scoped to stream processing (items flowing through a linear pipeline); Temporal supports arbitrary workflow graphs
- this engine is embedded in the application process; Temporal is a separate orchestration service
- no workflow query API, signal API, or timer API — only ingestion and worker loops

### Relationship To DataFusion

DataFusion is relevant only as inspiration for the logical/physical plan separation discipline. It is not the product being built here.

`streams` is a durable workflow and stream-processing library focused on operational correctness, recovery, retries, leasing, and adapter boundaries. It is not a SQL engine, not a query planner, and not a columnar execution engine.

---

## Trigger To Revisit These Features

Revisit future planning features only after the MVP proves:

- the core contracts are stable
- the loyalty reference flow works end to end in INFINITE mode
- the AMQP adapter integrates cleanly
- restart and recovery semantics are correct
- storage growth is bounded under production workload

At that point, the next likely candidates are:

1. observability tooling + `attempts` table (Section 7)
2. CLI graph visualizer — `pipekit graph` (extends the Phase 7 CLI; see `streams-delivery-phases.md` Phase 7 and Section 7 above)
3. `BOUNDED` mode if a batch-boundary use case emerges (Section 0)
4. plan/introspection support (Section 1)
5. kt-framework integration (Section 6)

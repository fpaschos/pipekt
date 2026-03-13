# Streams Core Architecture

## Summary

This document defines the target architecture for the new parallel `streams` library inside `pipekt`.

The goal is to create a framework-agnostic durable stream-processing library that can later be integrated with kt framework, RabbitMQ, and the loyalty workflow without coupling the core engine to any of them.

For v1, the library uses a minimal separation between:

- a logical pipeline definition
- an executable runtime plan

This is not a full DataFusion-style logical/physical planning system. The split exists to keep definition concerns separate from runtime concerns, not to introduce an optimizer or a physical planner in the MVP.

V1 supports only `INFINITE` pipelines — continuous ingress with no batch boundary, no barrier, and no finalizer. `BOUNDED` mode is explicitly deferred.

---

## Positioning

### What This Engine Has That In-Memory Engines Do Not

In-memory stream engines (Akka Streams, Project Reactor) provide rich operator sets and true reactive backpressure but have no durability. A process crash loses all in-flight work. There is no concept of a work item that survives restart, no lease, no per-item retry from a durable record.

This engine's differentiating properties:

- **durable item state** — every work item is persisted in Postgres; no item is lost on crash
- **restart safety** — the engine resumes from store state alone; no in-memory coordination survives restart
- **lease-based crash recovery** — items claimed by a crashed worker are reclaimed by the watchdog and retried

### What This Engine Does Not Have (Compared To Kafka Streams / Flink)

Kafka Streams and Apache Flink support DAG topologies, time-windowed aggregations, stateful joins, and exactly-once semantics via distributed checkpointing. These capabilities are not part of v1 and are explicitly deferred:

- DAG topology (branch, merge, fanout) — requires the pipeline definition to become a graph, not a list
- windowed aggregation — requires time-window state that the current item model does not support
- stateful joins across streams — requires cross-item state beyond what `payloadJson` carries

These are deferred not because they are impossible on this architecture, but because the loyalty use case does not need them and they would significantly increase implementation complexity before the core correctness properties are proven.

### Closest Conceptual Match: Temporal.io

The closest existing system in concept is **Temporal.io** — durable workflow execution with retries, leases, and step-level checkpointing. The key differences:

- this engine runs on Postgres instead of Temporal's proprietary persistence layer
- this engine is scoped to stream processing (items flowing through a linear pipeline) rather than arbitrary workflow graphs
- this engine is embedded in the application process rather than a separate orchestration service
- there is no workflow query API, signal API, or timer API — only the ingestion loop and worker loops

The Temporal comparison is useful for explaining the engine to engineers familiar with durable execution concepts. It is not an implementation dependency.

---

## Package Layout

### `io.github.fpaschos.pipekt.core`

Responsibilities:

- pipeline definition DSL
- operator model
- pipeline validation
- step contracts and execution context
- retry and timeout policy models
- serialization boundary contracts (`PayloadSerializer` interface — engine-level serialization abstraction; default implementation uses `kotlinx.serialization`; swappable for KMP targets)

Must not depend on:

- RabbitMQ client
- JDBC/Postgres
- Koin
- kt framework runtime types

### `io.github.fpaschos.pipekt.runtime`

Responsibilities:

- compile validated definitions into executable runtime structures
- run the ingestion loop: countNonTerminal check → poll → bulk append → ack
- enforce backpressure: pause ingestion when `countNonTerminal(runId) >= maxInFlight`
- run per-step worker loops: claim → execute → checkpoint
- run the watchdog loop: periodically reclaim expired leases
- concurrency and backpressure control per step
- run/item lifecycle orchestration
- retry timing and lease reclaim logic

Depends on:

- `pipekt.core`
- `pipekt.store`

### `io.github.fpaschos.pipekt.store`

Responsibilities:

- durable store SPI
- store-owned models for run/item state
- lease and claim contracts
- atomic checkpoint and persistence contracts

Initial design target:

- interface-only in the core planning pass
- Postgres implementation later in a JVM-specific package or subtree

### `io.github.fpaschos.pipekt.adapters.amqp`

Responsibilities:

- AMQP source adapter implementing the generic source contract
- durable append before ack behavior
- broker-specific metadata mapping
- topology concerns needed for source ingestion

This package must not become the engine entrypoint. It is an adapter only.

### `io.github.fpaschos.pipekt.examples.loyalty`

Responsibilities:

- reference workflow for the loyalty business process
- concrete step definitions using generic operators
- acceptance-level validation of the new API
- demonstrates an INFINITE (continuous ingress) pipeline

This example proves the generic design. It does not define the core abstractions.

---

## Minimal Layer Model

The architecture is intentionally split into four layers:

1. Definition layer
- build a pipeline definition
- declare operators
- validate operator ordering and type boundaries

2. Runtime layer
- execute runnable work via separate ingestion, worker, and watchdog loops
- coordinate concurrency, retries, backpressure, and reclaim

3. Store layer
- persist source of truth for progress and recovery

4. Adapter layer
- connect transports and external systems to the generic engine

---

## V1 Planning Model

V1 uses a small compile boundary:

1. `PipelineDefinition`
- user-facing logical definition
- contains operators, policies, and validation metadata

2. Internal executable plan (not a public API in v1)
- runtime may derive a runtime-ready form from a validated definition (resolved step ordering, execution metadata)
- this plan stays internal to `pipekt.runtime`; there is no public `ExecutablePipeline` type in v1

This compile step is intentionally simple:

- no rule optimizer
- no physical planner abstraction
- no alternative execution strategies
- no cost-based decisions

The purpose is only to avoid mixing DSL concerns with runtime orchestration.

---

## Canonical Data Flow

### INFINITE Pipeline Flow

1. Ingestion loop checks `countNonTerminal(runId)`; if `>= maxInFlight`, pauses and waits.
2. AMQP source polls a batch of records.
3. Source adapter maps transport records into ingress records.
4. Runtime durably appends the full batch to the store (`appendIngress` — bulk, idempotent).
5. Only after durable append succeeds does the adapter ack the transport records.
6. Per-step worker loops claim runnable items (`claim` — atomic, `FOR UPDATE SKIP LOCKED`).
7. Step functions execute with engine-managed retry and timeout policy.
8. Store checkpoints success or failure atomically (attempt counter + item state + payload in one transaction).
9. On terminal checkpoint (`COMPLETED`, `FILTERED`, `FAILED`), `payload_json` is nulled immediately.
10. The run never reaches a terminal state. Items are individually terminal; the run is a long-lived grouping key.
11. Background archival job periodically deletes or archives terminal `work_items` rows older than N days.

The three runtime loops (ingestion, worker-per-step, watchdog) are independent coroutines. They do not call each other.

---

## Core Operator Set For V1

The initial operator set is intentionally small:

- `source`
- `step`
- `filter`
- `persistEach`

Operator intent:

- `source` declares ingress contract, not transport implementation
- `step` performs typed business transformation
- `filter` removes items from further processing without treating them as failures
- `persistEach` enforces durable checkpoint visibility after a phase; enables mid-pipeline restart

`barrier` and `finalizeRun` are not part of v1. They are deferred to a future `BOUNDED` mode extension.

---

## Separation Rules

The following separations are mandatory:

- pipeline definition vs runtime execution
- validated logical pipeline vs executable runtime plan
- item-scoped work vs run-scoped work
- transport metadata vs business payload
- transient runtime state vs durable progress state
- framework composition vs engine contracts
- ingestion loop vs worker loops vs watchdog loop

---

## Architecture Constraints

- The engine must be restart-safe from store state alone.
- In-memory queues are optimization only; they are not the source of truth.
- `payload_json` is nullable; it must be nulled at the terminal checkpoint for `COMPLETED`, `FILTERED`, and `FAILED` items — this is an engine invariant, not optional.
- `resumeRuns` must call `reclaimExpiredLeases` before re-executing any active run.
- Adapter code may be JVM-specific; core contracts must remain free of JVM-only transport concerns.
- The run is long-lived and never transitions to a terminal state; items are individually terminal.

---

## Deferred By Design

The following are explicitly out of scope for the first pass:

- `BOUNDED` pipeline mode
- `barrier` operator
- `finalizeRun` operator
- `finalizer_locks` table
- `FINALIZED` and `AWAITING_BARRIER` run states
- kt-framework lifecycle integration
- production DI wiring
- app startup/bootstrap conventions
- advanced planner and optimizer layers
- physical execution planner abstractions
- DataFusion-style optimizer rules
- SQL dialect abstraction beyond first Postgres target
- Kafka adapter work
- UI and rich observability surfaces
- full `attempts` table (deferred to observability phase — see `future/streams-future-planning-features.md`)
- CLI tooling (`pipekit`) — Phase 7; depends on Phase 6 admin HTTP endpoints

---

## Consequences For Implementation

- `pipekt.core` is implemented first.
- `pipekt.core` produces a validated definition, not a physical execution tree.
- `pipekt.runtime` must consume generic source/store contracts rather than RabbitMQ handlers.
- `pipekt.runtime` may compile definitions into an internal executable plan, but that plan stays a runtime implementation detail in v1.
- `pipekt.runtime` runs three independent coroutine loops: ingestion, per-step workers, and watchdog.
- `pipekt.adapters.amqp` adapts RabbitMQ into ingress records and ack behavior only.
- kt framework gets a separate integration document and package later, after the loyalty example proves the API shape.

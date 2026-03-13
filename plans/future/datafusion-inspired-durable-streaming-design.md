# DataFusion-Inspired Durable Streaming Design (Kotlin)

> Status: future-planning reference only. This document is not the v1 architecture baseline for `streams`.

## 1) Intent

This document extends (does not replace) the durable pipeline plan in
`archive/durable-pipeline-implementation-plan.md`.

It is now explicitly outside the current MVP scope for `gr.hd360.sapp.loyalty.streams`.

Goal:

- Keep the durable workflow/checkpoint architecture.
- Add a DataFusion-inspired planning/execution structure for better ergonomics, extension points, and runtime discipline.
- Stay row/message oriented for now (no Arrow in-memory format yet).

Current interpretation:

- use this as inspiration for future extensions
- do not use it to justify a full logical/physical planner in v1

## 2) Verified Reference Context

- DataFusion is an extensible query engine with logical plans, physical execution plans, optimizer rules, and broad extension points.
- DataFusion emphasizes lazy plan construction and optimized execution.
- The original DataFusion author is Andy Grove.
- Andy Grove’s book is **How Query Engines Work**, built around a Kotlin implementation (`KQuery`) and available as free HTML + Leanpub edition.

Primary references:

- https://datafusion.apache.org/
- https://datafusion.apache.org/library-user-guide/building-logical-plans.html
- https://datafusion.apache.org/library-user-guide/query-optimizer.html
- https://andygrove.io/
- https://andygrove.io/how-query-engines-work/
- https://howqueryengineswork.com/
- https://leanpub.com/how-query-engines-work

## 3) What to Borrow from DataFusion (Without Arrow)

Adopt:

1. Logical vs physical separation.
- Logical pipeline: what should happen (`filter`, `map`, `barrier`, `checkpoint`, `sink`).
- Physical pipeline: how it runs (worker counts, queue sizes, retry scheduler, store claim strategy).

2. Rule-based optimization.
- Add optimizer passes over logical plan before execution.
- Example rules: fuse adjacent maps, push filters before expensive API calls, remove no-op checkpoints.

3. Session/Context model.
- Introduce `PipelineSession` as top-level runtime boundary (config, adapters, codecs, registry).
- Similar ergonomic role to DataFusion `SessionContext`.

4. Extension points first.
- Adapter interfaces for sources/sinks/checkpoint stores/retry policies.
- Custom logical operators and custom physical executors.

5. Invariants and validation.
- Validate plan and runtime invariants before launch (acyclic graph, barrier correctness, serializer compatibility, idempotency key presence).

Do not adopt yet:

- Arrow columnar batches.
- SQL parser/planner.
- Complex join/repartition optimizations.

## 4) Proposed API Shape (DataFusion-Inspired)

## 4.1 Layered API

1. Builder/DSL API (logical plan):
- `pipeline { source; step; filter; checkpoint; barrier; sink }`

2. Planner API:
- `LogicalPlan -> OptimizedLogicalPlan`

3. Physical planner API:
- `OptimizedLogicalPlan -> PhysicalPlan`

4. Runtime API:
- `PhysicalPlan -> RunningPipeline`

## 4.2 Core Interfaces

```kotlin
interface PipelineSession {
  val registry: AdapterRegistry
  val optimizer: LogicalOptimizer
  val physicalPlanner: PhysicalPlanner
  val runtime: PipelineRuntime
}

interface LogicalOptimizer {
  fun optimize(plan: LogicalPlan, ctx: OptimizeContext): LogicalPlan
}

interface PhysicalPlanner {
  fun plan(logical: LogicalPlan, ctx: PlanContext): PhysicalPlan
}

interface PipelineRuntime {
  suspend fun start(plan: PhysicalPlan): RunningPipeline
}
```

## 4.3 Step Semantics

Keep Arrow-style typed error ergonomics:

```kotlin
typealias StepFn<I, O, E> =
  suspend context(arrow.core.raise.Raise<E>, StepCtx) (I) -> O
```

## 5) Operator Model for Your Use Case

Logical operators to support in v1:

- `Source(batchSize = 50)`
- `Filter` (seq-id eligibility REST call)
- `Map` (download policy details)
- `Checkpoint` (persist intermediate row per policy)
- `MapSequential` (phase2 one-by-one)
- `BarrierAllSucceeded`
- `MapOncePerRun` (phase3 final call)
- `FinalizeRun`

This directly models:

1. AMQP policies in batches.
2. Per-policy filtering and enrichment.
3. Durable intermediate writes.
4. One-by-one second phase.
5. Single final operation after all success.
6. Final run log.

## 6) Physical Execution Strategy

## 6.1 Physical Nodes

- `IngressNode` (source polling + durable append)
- `WorkerNode` (parallel step execution)
- `CheckpointNode` (store writes and status transitions)
- `BarrierNode` (run-level aggregation gate)
- `RunFinalizerNode`

## 6.2 Backpressure

Backpressure contracts:

- Bounded ingress queue.
- Per-node concurrency caps.
- Global `maxInFlight` semaphore.
- `SUSPEND` overflow policy.
- Claim throttling from store when queues are saturated.

This gives predictable memory behavior in JVM and Kotlin/Native-compatible coroutine semantics.

## 6.3 Acquire/Release Patterns

- Acquire permit before network/DB-heavy step.
- Release permit on completion/failure in `finally`.
- Lease acquisition for DB work items with explicit expiry.
- Worker heartbeat extends lease; expired leases are reclaimable.

## 7) Persistence and Checkpoint Model

Retain the generic durable model from the main plan:

- `runs`
- `items`
- `attempts`

Add DataFusion-inspired execution metadata:

- `logical_plan_hash`
- `physical_plan_hash`
- `optimizer_version`
- `operator_id` per attempt row

Why:

- Enables reproducible debugging and plan evolution safety.
- Supports replay with exact plan version tracing.

## 8) Optimizer Rules (Initial Set)

Rule set for streaming durable pipelines:

1. `FilterPushDownRule`
- Move filters earlier where no side effects.

2. `CheckpointPruneRule`
- Remove redundant checkpoints when no recovery gain.

3. `SequentialCoalesceRule`
- Merge adjacent sequential nodes to reduce queue hops.

4. `BarrierPlacementValidationRule`
- Ensure barrier only depends on finite predecessor set.

5. `RetryIsolationRule`
- Prevent sharing retry state across unrelated operators.

## 9) Adapter Roadmap (AMQP now, Kafka next)

Source SPI:

```kotlin
interface SourceAdapter<T> {
  suspend fun poll(maxItems: Int): List<SourceRecord<T>>
  suspend fun ack(records: List<SourceRecord<T>>)
  suspend fun nack(records: List<SourceRecord<T>>, retry: Boolean)
}
```

Current:

- AMQP adapter in `jvmMain` using RabbitMQ Java client.

Next:

- Kafka adapter in `jvmMain` using Apache Kafka client with commit strategy aligned to durable append/checkpoint boundaries.

## 10) Actors/Channels Guidance

- Prefer Flow pipeline as primary public model.
- Use channels internally for mailbox-style components:
- lease manager
- retry scheduler
- barrier coordinator
- adapter prefetch buffer

Expose actor behavior as library features, not as raw actor APIs.

## 11) Implementation Phases (DataFusion-Inspired Extension)

Phase A: Planning Layer

- Add `LogicalPlan` AST for pipeline operators.
- Add `LogicalOptimizer` and first rule set.
- Add `PhysicalPlan` compiler.

Phase B: Runtime Refactor

- Execute `PhysicalPlan` nodes, not direct DSL chain.
- Add operator IDs and plan hashes to telemetry and attempts.

Phase C: Checkpoint Enhancements

- Persist plan metadata with runs.
- Add replay mode: resume with same plan or fail fast on incompatible plan.

Phase D: Adapter Expansion

- Harden AMQP adapter contracts.
- Add Kafka source adapter preview.

Phase E: Ergonomics

- `PipelineSession` presets.
- Sensible defaults profiles: `low-latency`, `balanced`, `durable-heavy`.

## 12) Where to Start (Concrete Next Steps)

1. Implement `LogicalPlan` + DSL-to-plan compiler first.
2. Add two optimizer rules only: filter pushdown + barrier validation.
3. Implement physical planner that maps operators to current durable runtime.
4. Persist `plan_hash` in `runs` and `operator_id` in `attempts`.
5. Keep AMQP + policy use case as acceptance path until stable.

## 13) Acceptance Criteria for this Extension

- Existing policy workflow runs unchanged from user perspective.
- Plan visualization available (`logical` and `physical` printable trees).
- Optimizer can be disabled/enabled per run.
- Recovery succeeds with plan hash continuity checks.
- Backpressure limits are enforced and visible in metrics.

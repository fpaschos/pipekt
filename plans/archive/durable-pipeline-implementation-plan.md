# Durable Kotlin Flow Pipeline with Persistence

> Status: superseded as the active MVP baseline.
>
> This document is retained as historical context only. The current implementation baseline for `sapp-loyalty` is the `streams-*` planning set:
> - `streams-current-state-and-legacy-boundary.md`
> - `streams-core-architecture.md`
> - `streams-contracts-v1.md`
> - `streams-loyalty-reference-flow.md`
> - `streams-delivery-phases.md`
>
> Use this document only for background ideas that do not conflict with the `streams-*` plans.

## 1) Objective and Scope

Build a general-purpose library that provides:

- A high-level Kotlin DSL for defining durable, restartable data pipelines.
- Arrow `Raise`-based step semantics with `suspend context(...)`.
- Flow-native execution with explicit backpressure.
- Durable persistence for intermediate and final state.
- JVM AMQP source adapter, with common logic reusable from `commonMain`.

Primary first use case:

- Read policy messages from AMQP in batches (50).
- Filter via REST call using sequence ID.
- Download policy details via REST.
- Persist per-policy intermediate results to DB.
- Execute second phase one-by-one per policy.
- Execute a final phase once all policies in run succeed.
- Persist final run log and recover from mid-run failure.

Out of scope for MVP:

- Full exactly-once guarantees across all external side effects.
- Multiple SQL dialect implementations beyond Postgres.
- UI/observability dashboards (basic metrics/logging only in MVP).

## 2) Design Principles

- Separate business workflow from durability/runtime concerns.
- One generic persistence contract for all use cases (no per-domain store interface).
- Backpressure must be explicit and testable at ingress, step, and global levels.
- Idempotency by design (`run_id + item_key` uniqueness).
- JVM-first adapters for AMQP and SQL; keep core abstractions multiplatform-friendly.

## 3) Top-Level Library Architecture

## 3.1 Modules

1. `durable-pipeline-core` (`commonMain`)
- DSL (`pipeline { step/filter/barrier/finalize }`).
- Pipeline graph and compile-time validations.
- Arrow `Raise` step contracts.
- Error taxonomy and retry policy interfaces.

2. `durable-pipeline-runtime-flow` (`commonMain`)
- Flow-based execution engine.
- Worker scheduler, lease heartbeats, retry timers.
- Backpressure controls (buffers, semaphores, concurrency).

3. `durable-pipeline-store-spi` (`commonMain`)
- Generic durable store interfaces.
- Data models for `Run`, `WorkItem`, `Attempt`, `Checkpoint`.

4. `durable-pipeline-store-sql-jvm` (`jvmMain`)
- Postgres implementation via JDBC/driver abstraction.
- SQL dialect abstraction (start with Postgres dialect).
- Migrations for `runs/items/attempts`.

5. `durable-pipeline-source-amqp-jvm` (`jvmMain`)
- AMQP consumer adapter (batch support).
- Ack strategy integrated with durable append semantics.

6. `durable-pipeline-serialization` (`commonMain`)
- `kotlinx.serialization` envelope/version codecs.

7. `durable-pipeline-testing` (`commonTest`/`jvmTest`)
- Fakes, harnesses, deterministic scheduler/time controls.

## 3.2 Responsibilities Split

- DSL expresses business steps only.
- Runtime executes, retries, and applies backpressure.
- Store is source of truth for progress and recovery.
- Source adapter is transport-specific and pluggable.

## 4) Public API and DSL Plan

## 4.1 Core Types

- `StepFn<I, O, E> = suspend context(Raise<E>, StepCtx) (I) -> O`
- `StepCtx`: `runId`, `itemId`, `step`, `attempt`, clock, metadata.
- `PipelineError`: `Retryable`, `Fatal`, `FilteredOut` (extendable by users).
- `RetryPolicy`: backoff strategy + max attempts.

## 4.2 DSL Shape (Target)

```kotlin
val engine = pipeline<PolicyMsg>("policy-sync") {
  source(amqpSource(batchSize = 50))
  store(sqlStore)
  serialization(jsonCodec)
  limits {
    claimBatch = 50
    ingressBuffer = 200
    maxInFlight = 64
  }

  step<PolicyMsg, PolicyMsg, PolicyErr>("seq-filter", concurrency = 8) { msg ->
    ensure(restSeq.isAllowed(msg.seqId)) { PolicyErr.FilteredOut(msg.policyNo) }
    msg
  }

  step<PolicyMsg, PolicyDetails, PolicyErr>("download-details", concurrency = 8) { msg ->
    restDetails.fetch(msg.policyNo)
  }

  persistEach("details-checkpoint")

  step<PolicyDetails, Phase2Result, PolicyErr>("phase2-one-by-one", concurrency = 1) { details ->
    restPhase2.send(details)
  }

  barrier("all-phase2-success")

  step<Unit, Unit, PolicyErr>("phase3-final", concurrency = 1) {
    restPhase3.callForRun(stepCtx.runId)
  }

  finalizeRun("policy-final-log")
}
```

## 4.3 DSL Ergonomics Requirements

- Type-safe step chaining and serialization boundaries.
- Optional step-level config:
- `concurrency`, `retryPolicy`, `timeout`, `idempotencyKey`, `tags`.
- Built-in operators:
- `step`, `filter`, `map`, `persistEach`, `barrier`, `finalizeRun`.
- Context injection:
- `context(Raise<E>, StepCtx)` mandatory for business step lambdas.

## 4.4 API Stability Strategy

- Keep public interfaces minimal and explicit.
- Mark experimental operators with annotations in early releases.
- Versioned serialization envelopes for forward migration.

## 5) Persistence Model Plan

## 5.1 Generic Tables (MVP)

1. `dp_runs`
- `run_id`, `pipeline`, `status`, `created_at`, `updated_at`.

2. `dp_items`
- `item_id`, `run_id`, `item_key`, `step`, `status`, `attempt`.
- `payload_json`, `error_json`, `retry_at`.
- `lease_owner`, `lease_until`, `updated_at`.
- Unique: `(run_id, item_key)`.

3. `dp_attempts`
- attempt history with per-step inputs/outputs/errors and timing.

## 5.2 Store SPI Contract

Core operations:

- `createRun(pipeline): RunId`
- `appendIngress(runId, records)`
- `claim(step, limit, lease, workerId): List<WorkItem>`
- `checkpointSuccess(item, output, nextStep)`
- `checkpointFailure(item, error, retryAt)`
- `pending(runId): List<WorkItem>`
- `finalizeRun(runId, status)`

## 5.3 Recovery Rules

- Resume from persisted `step/status` only.
- Reclaim expired leases.
- Skip completed/filtered items.
- Retry only retryable failures with backoff.

## 5.4 Idempotency Rules

- One logical item per `(run_id, item_key)`.
- Attempt increments on retried failures.
- External calls should pass idempotency key when supported.

## 6) Backpressure Integration Plan

Backpressure is enforced at three levels:

1. Ingress
- Source batch size (`claimBatch`/AMQP batch).
- Bounded ingress queue (`buffer` or channel with `SUSPEND`).

2. Step execution
- Per-step `concurrency`.
- `flatMapMerge(concurrency = N)` and bounded step buffers.

3. Global system
- Global `Semaphore(maxInFlight)` across all steps.
- Claim throttling from DB when in-flight reaches threshold.

Behavioral expectation:

- If REST/DB slows, downstream suspends.
- Suspension propagates upstream to ingestion and AMQP pull/ack rate.

## 7) Runtime Execution Model

## 7.1 Lifecycle

1. Start or resume run.
2. Ingest source batch and durably append items.
3. Worker loops claim runnable items per step.
4. Execute step with Arrow `Raise` + `suspend`.
5. Persist attempt + checkpoint.
6. Move item to next step or terminal status.
7. Evaluate barriers.
8. Finalize run when terminal conditions met.

## 7.2 Failure Handling

- Retryable error -> `RETRY` with `retry_at`.
- Fatal error -> `FAILED` terminal for item.
- Worker crash -> lease expiry -> re-claim.
- Process restart -> reload pending items and continue.

## 8) Multiplatform Strategy

## 8.1 What lives in `commonMain`

- DSL and pipeline graph.
- Runtime orchestration and backpressure logic.
- Store/source/serialization interfaces.

## 8.2 What lives in `jvmMain`

- AMQP Java client source adapter.
- SQL/JDBC store implementation.
- Production migration tooling.

## 8.3 Expected Constraint

- AMQP adapter is JVM-only; core remains reusable for non-AMQP sources in KMP.

## 9) Implementation Phases

## Phase 0: Foundation and Contracts

Deliverables:

- Project module scaffolding.
- Core models (`Run`, `Item`, `Attempt`, statuses).
- Store SPI and source SPI.
- Serialization envelope + codec interfaces.

Exit criteria:

- Compiles with API docs and baseline tests.

## Phase 1: DSL and Pipeline Graph

Deliverables:

- `pipeline {}` builder.
- `step/filter/persistEach/barrier/finalizeRun` operators.
- Validation for graph/step name uniqueness and terminal states.

Exit criteria:

- DSL can define the policy use case without runtime execution.

## Phase 2: Flow Runtime + Backpressure

Deliverables:

- Step worker engine.
- Bounded flow buffers and per-step concurrency.
- Global max-in-flight control.
- Retry scheduler and timeout hooks.

Exit criteria:

- Runtime executes in-memory fake store with deterministic tests.

## Phase 3: SQL Store (Postgres JVM)

Deliverables:

- Schema migrations (`dp_runs`, `dp_items`, `dp_attempts`).
- `claim/checkpoint/retry/resume/finalize` queries.
- Lease/heartbeat and reclaim logic.

Exit criteria:

- Crash-resume integration test passes against Postgres.

## Phase 4: AMQP JVM Source Adapter

Deliverables:

- Batch consumer adapter (`batchSize=50`).
- Durable append before ack strategy.
- Source health and reconnect policy.

Exit criteria:

- End-to-end AMQP -> DB -> runtime loop integration test passes.

## Phase 5: Policy Use Case Reference Implementation

Deliverables:

- Full policy workflow pipeline definition.
- Phase2 one-by-one and phase3 barriered final call.
- Final run log persistence.

Exit criteria:

- Replay/recovery from each middle step is proven.

## Phase 6: Hardening and Release

Deliverables:

- Metrics/logging/tracing hooks.
- Load/perf tests for backpressure behavior.
- Versioned release docs and migration notes.

Exit criteria:

- Ready for internal adoption with runbook.

## 10) Testing Strategy

## 10.1 Unit tests

- Step semantics (`Raise` success/failure/filter).
- Retry policy decisions.
- DSL graph validation.

## 10.2 Integration tests

- Runtime + fake source/store.
- Runtime + Postgres store.
- Runtime + AMQP test broker.

## 10.3 Reliability tests

- Kill/restart at every step boundary.
- Lease expiry and worker takeover.
- Duplicate message/idempotency checks.

## 10.4 Backpressure tests

- Slow downstream API simulation.
- Verify ingress stalls when buffers/concurrency are saturated.
- Verify memory/in-flight bounds under sustained load.

## 11) Observability and Operability

MVP metrics:

- `items_claimed`, `items_succeeded`, `items_failed`, `items_retried`.
- `inflight_count`, `queue_depth`, `claim_latency`, `step_duration`.
- `lease_reclaims`, `barrier_wait_time`.

MVP logs:

- run lifecycle events.
- step attempt start/end/error with run/item identifiers.

Runbook essentials:

- How to restart safely.
- How to inspect stuck items.
- How to replay specific failed items.

## 12) Risks and Mitigations

- Risk: exactly-once assumptions with external systems.
- Mitigation: explicit at-least-once semantics + idempotency keys.

- Risk: DB hot spots on claim/update.
- Mitigation: indexes, batched claims, SKIP LOCKED, partitioning later.

- Risk: schema evolution for payloads.
- Mitigation: versioned envelopes and compatibility tests.

## 13) Deliverable Checklist

- [ ] DSL and public API documented.
- [ ] Runtime backpressure controls implemented.
- [ ] Store SPI stable and reviewed.
- [ ] Postgres store and migrations complete.
- [ ] AMQP JVM source adapter complete.
- [ ] Policy reference pipeline implemented.
- [ ] Recovery/backpressure/idempotency tests passing.
- [ ] Operations runbook and release notes complete.

## 14) Suggested Implementation Order (Week-by-Week)

Week 1:

- Modules + SPI contracts + core models + serialization envelope.

Week 2:

- DSL builder + graph validation + unit tests.

Week 3:

- Flow runtime + backpressure + retry engine + fake-store tests.

Week 4:

- Postgres store + migrations + recovery integration tests.

Week 5:

- AMQP adapter + end-to-end test with policy pipeline.

Week 6:

- Hardening, load tests, docs, release candidate.

## 15) Ecosystem Baseline (Use Existing Libraries First)

Goal: maximize ergonomics and reduce custom concurrency code.

Core runtime and concurrency:

- `kotlinx.coroutines` + `Flow` as primary stream runtime.
- `Channel` for explicit mailbox/actor boundaries only.
- `Semaphore` with `withPermit` for acquire/release limits.
- Avoid deprecated `actor` builder in new public API; expose mailbox abstraction backed by channels.

Typed errors and DSL ergonomics:

- Arrow `Raise` for step contracts and error flow.
- Keep `suspend context(Raise<E>, StepCtx)` as the canonical step signature.

Persistence and serialization:

- `kotlinx.serialization` for payload/error envelopes with version fields.
- Start with JVM SQL implementation (Postgres).
- If KMP SQL is required later, add optional SQLDelight-backed store adapter.

HTTP and retries/backoff:

- Ktor client for REST calls in steps.
- Retry/backoff in engine policy (own `RetryPolicy`) with optional adapter to Arrow Resilience schedules.

Messaging adapters:

- AMQP: RabbitMQ Java client adapter in `jvmMain`.
- Kafka future adapter: Apache Kafka Java client in `jvmMain` first.
- Keep source SPI generic so transport adapters are pluggable.

Testing:

- `kotlinx-coroutines-test` for deterministic coroutine/virtual time tests.
- Turbine for flow assertions.
- Testcontainers for integration tests (Postgres, RabbitMQ, Kafka).

## 16) Revised Starting Point for the Larger Stream Library

Start sequence:

1. Lock DSL and error model first.
- Freeze `StepFn` signature and operator set (`step/filter/persistEach/barrier/finalize`).
- Define stable `PipelineError` and retry policy contracts.

2. Implement runtime control plane before adapters.
- In-memory store + fake source.
- Global in-flight + per-step concurrency + bounded buffers.
- Prove backpressure behavior with stress tests.

3. Implement durable store next.
- Postgres schema and claim/checkpoint semantics.
- Lease/reclaim and restart recovery tests.

4. Add AMQP adapter.
- Durable append-before-ack ingestion model.
- Batch handling and reconnection behavior.

5. Add policy reference pipeline.
- Use case-specific step logic only; no custom infra.

6. Add Kafka adapter after AMQP path is stable.
- Reuse same source SPI and runtime/store layers.

7. Optional KMP expansion phase.
- Keep core/runtime common.
- Add optional KMP store/source adapters where ecosystem is mature enough.

Definition of done for this broader update:

- Stable DSL v1.
- Runtime backpressure contracts verified under load.
- Crash/restart correctness across all step boundaries.
- AMQP production path and Kafka preview adapter both working.

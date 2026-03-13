# MVP Implementation Plan (Experiment-First)

> Status: superseded as the active MVP baseline.
>
> This document is retained as historical context only. The current implementation baseline for `pipekt` is the `streams-*` planning set:
> - `streams-current-state-and-legacy-boundary.md`
> - `streams-core-architecture.md`
> - `streams-contracts-v1.md`
> - `streams-loyalty-reference-flow.md`
> - `streams-delivery-phases.md`
>
> Use this document only for background ideas that do not conflict with the `streams-*` plans.

## 1) Goal

Deliver a minimal durable streaming library for your policy use case that is:

- simple enough to implement now,
- generic enough to extend (Kafka, new steps),
- testable for restart, backpressure, and retries.

## 2) Scope for MVP

Include:

- Kotlin DSL with typed steps (`suspend context(Raise<E>, StepCtx)`).
- Flow runtime with bounded backpressure.
- Durable DB checkpoints and resume.
- AMQP source adapter (JVM).
- Policy reference pipeline.

Exclude for now:

- Full logical/physical planner split.
- Arrow columnar memory format.
- Multi-dialect SQL support.

## 3) Module Layout

1. `pipeline-core` (`commonMain`)
- DSL and step contracts.
- Error/retry models.

2. `pipeline-runtime` (`commonMain`)
- Flow runner.
- Concurrency and backpressure controls.

3. `pipeline-store-spi` (`commonMain`)
- Generic durable store interface.

4. `pipeline-store-postgres-jvm` (`jvmMain`)
- SQL schema + store implementation.

5. `pipeline-source-amqp-jvm` (`jvmMain`)
- RabbitMQ adapter.

6. `pipeline-policy-example-jvm`
- Your concrete policy workflow and acceptance tests.

## 4) Public API to Implement First

```kotlin
typealias StepFn<I, O, E> =
  suspend context(arrow.core.raise.Raise<E>, StepCtx) (I) -> O

interface DurableStore {
  suspend fun createRun(pipeline: String): String
  suspend fun appendIngress(runId: String, records: List<IngressRecord>)
  suspend fun claim(step: String, limit: Int, leaseMs: Long, workerId: String): List<WorkItem>
  suspend fun checkpointSuccess(item: WorkItem, outputJson: String, nextStep: String?)
  suspend fun checkpointFailure(item: WorkItem, errorJson: String, retryAtEpochMs: Long?)
  suspend fun finalizeRun(runId: String, status: String)
}
```

DSL operators:

- `source(...)`
- `step(...)`
- `filter(...)`
- `persistEach(...)`
- `barrier(...)`
- `finalizeRun(...)`

## 5) Runtime Behavior (Must-Have)

- Bounded ingress queue.
- Per-step concurrency.
- Global `maxInFlight` semaphore.
- Overflow policy = suspend.
- Retry with exponential backoff.
- Lease-based claim/reclaim for crash recovery.

## 6) Policy Pipeline (Current Target)

1. Read AMQP policies in batches of 50.
2. Filter via seq-id REST check.
3. Download policy details REST call.
4. Persist per-policy intermediate checkpoint row.
5. Call second system one-by-one per policy.
6. After all policies succeeded in phase2, call final system once.
7. Write final run log row.

## 7) Implementation Phases

## Phase 1: Skeleton (2-3 days)

- Create modules and interfaces.
- Add in-memory fake store and fake source.
- Add baseline DSL compile/validation tests.

Exit:

- Pipeline compiles and runs fully in memory.

## Phase 2: Runtime + Backpressure (3-4 days)

- Implement Flow worker loops.
- Add bounded queues and `Semaphore`.
- Add retry and timeout plumbing.

Exit:

- Backpressure stress test passes in fake environment.

## Phase 3: Postgres Durability (4-5 days)

- Implement schema: `runs`, `items`, `attempts`.
- Implement claim/checkpoint/reclaim queries.
- Add restart recovery behavior.

Exit:

- Crash-restart test passes with Postgres.

## Phase 4: AMQP Adapter (2-3 days)

- RabbitMQ source adapter with batch ingestion.
- Durable append before ack behavior.

Exit:

- End-to-end AMQP -> DB -> runtime pipeline passes.

## Phase 5: Policy Acceptance Flow (2-3 days)

- Implement policy-specific steps.
- Implement barrier and final call logic.
- Add run summary row.

Exit:

- Full business flow test passes with fault injection.

## 8) Experiment Test Plan (Core of This Plan)

These tests are required so you can experiment and enhance safely.

## 8.1 Fast local tests (always run)

1. `step_success_and_raise_failure`
- Validate `Raise` semantics and error mapping.

2. `dsl_rejects_invalid_barrier`
- Barrier placement validation.

3. `retry_policy_exponential_backoff`
- Verify retry schedule and max attempts.

4. `backpressure_blocks_ingress_when_downstream_slow`
- Slow fake REST; assert producer suspension and bounded in-flight count.

## 8.2 Durable behavior tests

5. `resume_from_middle_after_restart`
- Stop service after details step; restart; continue from remaining.

6. `lease_expiry_reclaims_stuck_item`
- Simulate worker crash; another worker reclaims.

7. `idempotent_duplicate_policy_message`
- Same policy input twice; one logical item row.

8. `sequential_phase2_is_one_by_one`
- Ensure phase2 never exceeds one concurrent item.

9. `final_phase_runs_once_after_all_phase2_done`
- Barrier/final step correctness.

## 8.3 End-to-end acceptance test for your target

10. `policy_pipeline_full_acceptance_fault_injection`
- Input: N policies.
- Inject failures:
- intermittent seq-id service failure,
- intermittent details timeout,
- process stop mid-run.
- Assert final state:
- all eligible policies have final status,
- intermediate rows persisted,
- no duplicate final side effects.

## 9) Minimal Observability for Experiments

Expose counters/gauges:

- `inflight_count`
- `ingress_queue_depth`
- `items_succeeded`
- `items_failed`
- `items_retried`
- `lease_reclaims`
- `barrier_wait_ms`

Log IDs for every step attempt:

- `run_id`, `item_key`, `step`, `attempt`, `status`, `duration_ms`.

## 10) Experiment Harness

Create a dedicated module/test profile that lets you tune:

- batch size
- per-step concurrency
- max in-flight
- buffer sizes
- retry/backoff settings
- simulated external latency/error rates

Start with preset profiles:

- `safe`: low concurrency, high durability.
- `balanced`: moderate concurrency.
- `stress`: high ingress with slow downstream.

## 11) Definition of Done (MVP)

- Policy use case works end-to-end.
- Restart resumes from checkpoints.
- Backpressure is visible and bounded under stress.
- Retry/failure semantics are predictable.
- AMQP ingestion and DB durability are integrated.
- Acceptance test and 9 core tests above are passing.

## 12) Next Enhancements (After MVP)

1. Kafka adapter using same `SourceAdapter` SPI.
2. Lightweight internal logical plan printer/validator.
3. Optional SQLDelight-based KMP store adapter.
4. Extended operator set (`window`, `join-like enrichment`, `dead-letter sink`).

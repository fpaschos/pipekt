# Streams Future Plan: Runtime Ordering And Observability

**Status:** Future planning document.

**Purpose:** Capture the next-stage design for execution ordering, runtime observability, and efficient Postgres-backed coordination without expanding the v1 MVP prematurely.

**Precedence:** This document is lower precedence than [streams-contracts-v1.md](../streams-contracts-v1.md) and [current-implementation.md](../current-implementation.md). It records intended future direction only.

## Summary

This plan covers the first substantial runtime evolution after the current MVP:

- preserve logical pipeline/operator order deterministically
- add optional item ordering semantics without forcing global serialization
- make pipeline status inspectable and monitorable
- keep Postgres as the durable progress authority
- avoid hot-loop polling by combining durable claims with wake-up hints

The primary target workloads are:

- side-effect execution pipelines
- moderate-to-medium throughput domains
- examples such as earthquake enrichment and insurance-policy integration flows
- systems where correctness, retries, and operator visibility matter more than maximum raw throughput

This plan intentionally does **not** turn `pipekt` into a Kafka replacement or a general distributed streaming engine.

---

## 1. Design Goals

The future runtime should satisfy these goals:

- deterministic logical operator order in every `PipelineDefinition`
- unordered execution by default for throughput
- optional per-key ordered execution when business semantics require it
- durable correctness owned by the store, not by worker-local memory
- lightweight runtime inspection for active pipelines
- clear distinction between authoritative persisted state and derived snapshots
- efficient idle behavior without constant aggressive database polling

Non-goals:

- strict total ordering across all items in a pipeline
- a requirement for a public physical-plan API
- replacing Kafka or AMQP with Postgres as the primary transport
- cluster-wide elastic scheduling in the first version of this plan

---

## 2. Ordering Model

Ordering must be split into separate concerns:

1. **Topology order**
   - the source and operator chain remain ordered exactly as declared in the DSL
   - this is already the logical contract and must remain deterministic

2. **Item execution order**
   - the engine should not promise total global ordering by default
   - ordered execution must be explicit and optional

Recommended ordering modes:

- `UNORDERED`
  - maximum throughput
  - current default mental model

- `PARTITIONED`
  - FIFO within a partition key
  - unordered across partition keys
  - intended default ordered mode

- `SERIAL`
  - one-at-a-time execution for the whole step or pipeline
  - explicit opt-in escape hatch only

The recommended future guarantee is:

- preserve logical operator order always
- preserve per-key item order only when partitioned ordering is configured
- avoid promising global total order except in explicit serial mode

---

## 3. Partition Key Semantics

Partition ordering depends on a **stable business key**, not raw transport metadata.

Valid partition-key sources:

- payload/model fields such as `policyId`, `customerId`, `accountId`, `aggregateId`
- normalized ingress metadata extracted by the source adapter from transport headers and promoted into engine-owned metadata

Invalid or discouraged partition-key sources:

- broker delivery ids
- Kafka offsets
- AMQP delivery tags
- metadata that changes across replay or redelivery

The engine should treat these identifiers as distinct:

- `itemId`
  - engine-generated row identity

- `sourceId`
  - ingress deduplication and idempotency identity

- `partitionKey`
  - execution-ordering identity

These must not be conflated. A future implementation should allow cases such as:

- `sourceId = eventId`
- `partitionKey = policyId`

This means duplicate ingress is suppressed per event while ordered execution is preserved per policy.

---

## 4. Durable State Additions

If ordered execution becomes part of the engine contract, persisted item state must carry the routing information needed to enforce it.

Likely additive fields on `work_items`:

- `partition_key TEXT NULL`
- `partition_bucket INT NULL`
- `step_sequence BIGINT NOT NULL` or equivalent deterministic ordering token

Purpose of each field:

- `partition_key`
  - durable identity for FIFO grouping

- `partition_bucket`
  - routing optimization for worker assignment and indexed claims

- `step_sequence`
  - durable ordering token used to determine which item is older within a step

`step_sequence` is required if the store must enforce "older item for the same key must run first" semantics.

Without an explicit ordering token, ordered claims become ambiguous under retries, restarts, and concurrent ingress.

---

## 5. Durable Store Contract Changes

Partition ordering cannot remain a runtime-only convention. If it is a real engine guarantee, the `DurableStore` contract must encode it.

Future contract changes should:

- extend persisted ingress data with partition metadata
- make claim semantics explicit about ordered vs unordered behavior
- require ordered eligibility to be enforced by the store transactionally

Recommended direction:

```kotlin
sealed interface ClaimPolicy {
    data object Unordered : ClaimPolicy
    data class PartitionOrdered(
        val buckets: Set<Int>? = null,
    ) : ClaimPolicy
}
```

and evolve claim operations toward something like:

```kotlin
suspend fun claim(
    step: String,
    runId: String,
    limit: Int,
    leaseDuration: Duration,
    workerId: String,
    claimPolicy: ClaimPolicy,
): List<WorkItem>
```

Required store guarantee in ordered mode:

- the store must not lease an item if an older non-terminal item for the same `partitionKey` and step is still `PENDING` or `IN_PROGRESS`

This contract is necessary so custom store implementations cannot accidentally compile while violating ordering semantics.

---

## 6. Claim And Scheduling Semantics

The step function must remain unaware of partition enforcement.

Ordered execution should be implemented before `StepFn` invocation:

1. ingress computes `partitionKey`
2. runtime derives `partitionBucket`
3. item state is persisted durably
4. worker claim logic selects only eligible items
5. only then is the step invoked

Correctness boundary:

- runtime scheduling gives efficiency
- durable claim semantics give correctness

The runtime may optimize by assigning buckets to workers, but correctness must not depend solely on in-memory bucket ownership.

On restart or multi-worker execution, the store remains the authority that decides which item is eligible to run next.

---

## 7. Runtime Snapshots And Inspection

The current executable snapshot surface is too small for real operations. Future versions should add read models for inspection.

These snapshots are **derived read models**, not primary persisted entities.

Recommended split:

- `PipelineTopologySnapshot`
  - derived from `PipelineDefinition`
  - source, ordered operators, retry policy, types, execution mode

- `PipelineRuntimeSnapshot`
  - derived from durable state plus in-memory runtime state when available
  - run health, step counts, backpressure status, recent failures

- `StepRuntimeSnapshot`
  - per-step counters and health indicators

Recommended snapshot fields:

- pipeline identity
- plan version
- run id
- runtime health/status
- ingress state: polling, paused, last poll time, last ack time
- backpressure state and `countNonTerminal`
- per-step counts: pending, in-progress, retry-waiting, completed, filtered, failed
- recent error summaries
- lease reclaim health
- oldest pending age / oldest in-progress age

Snapshots should be assembled from:

- `RunRecord`
- `WorkItem`
- runtime actor memory for currently active executables

Snapshots should **not** become a second authoritative store.

---

## 8. DSL And Configuration Direction

Ordering should remain primarily an execution concern, not a topology-shape concern.

Recommended user model:

- logical DSL continues to describe source and operator chain
- execution ordering is configured separately, either in runtime config or in a dedicated execution block

Illustrative future direction:

```kotlin
pipeline<Event>(
    name = "policy-sync",
    maxInFlight = 5_000,
) {
    source("ingress", adapter)

    execution {
        ordering = Ordering.partitioned(
            partitionCount = 1_000,
            keySelector = { it.policyId },
        )
    }

    step("validate") { policy -> validate(policy) }
    step("enrich") { policy -> enrich(policy) }
    step("sink") { policy -> deliver(policy) }
}
```

Alternative shape:

```kotlin
definition.start(
    planVersion = "v2",
    config = RuntimeConfig(
        ordering = Ordering.Partitioned(
            partitionCount = 1_000,
            keySelectorName = "policyId",
        ),
    ),
)
```

The exact API can evolve, but the boundary should remain:

- pipeline DSL expresses *what happens*
- runtime config expresses *how execution is scheduled*

---

## 9. Partition Count And Hashing

The runtime should support many logical partitions without requiring one worker per partition.

Recommended model:

- compute `partitionBucket = stableHash(partitionKey) % partitionCount`
- allow `partitionCount` to be much larger than active worker count
- assign buckets to workers for routing efficiency

For the first version of this feature:

- use stable hash to bucket
- do not make consistent hashing or hash rings part of the public abstraction

Why:

- hash modulo is simple and deterministic
- the target workloads do not require cluster-grade dynamic rebalancing initially
- a future distributed runtime can adopt rendezvous hashing or a hash ring internally without changing the user-facing DSL

Logical buckets and active workers should remain distinct concepts.

---

## 10. Postgres Coordination Model

For the intended workloads, Postgres is the durable state machine and lease authority.

Recommended responsibility split:

- AMQP/Kafka/HTTP adapters handle ingestion transport
- Postgres stores run state, work-item state, leases, retries, and ordering metadata
- workers claim and checkpoint via SQL transactions

The engine should **not** rely on Postgres as the only transport for high-volume message delivery.

This architecture is suitable for:

- side-effect pipelines
- moderate throughput
- high correctness requirements
- auditability and operational inspection

It is not intended to replace Kafka-scale transport semantics end-to-end.

---

## 11. Polling, Notifications, And Wakeups

The runtime should avoid aggressive tight-loop polling, but it must not rely on notifications for correctness.

Recommended model:

- Postgres claim query remains authoritative
- `LISTEN/NOTIFY` is used only as a wake-up hint
- workers retain a fallback timer/backoff poll

Worker loop direction:

1. attempt durable claim
2. if work exists, continue immediately
3. if no work exists, wait for:
   - notification, or
   - bounded backoff timer
4. periodically sweep for:
   - expired leases
   - retry-ready items
   - missed notifications

This avoids hot polling while remaining robust to dropped or coalesced notifications.

`pgmq` is not the recommended primary abstraction for this engine because the engine requires:

- step-aware claims
- lease ownership
- retry scheduling
- partition ordering
- checkpointed progression between steps

Those semantics fit better in dedicated engine tables than in a generic queue abstraction.

---

## 12. Performance Expectations And Scope

This plan targets workloads such as:

- earthquake enrichment
- policy ingestion and filtering
- outbound sink integrations
- thousands per day up to moderate sustained throughput

For these workloads, a Postgres-backed runtime should be fast enough if it uses:

- batched ingress
- batched claims
- indexed claim paths
- short retention for terminal items
- optional partition ordering only when needed
- early filtering before expensive sinks

Likely future bottlenecks:

- globally ordered execution
- hot partition keys
- broad polling scans
- large retained terminal payloads
- missing or poorly targeted indexes

The recommended optimization order is:

1. correct durable contracts
2. usable runtime inspection
3. indexed Postgres claims
4. adaptive polling plus notification hints
5. partition ordering only for steps that truly need it

---

## 13. Recommended Delivery Sequence

The future implementation should proceed in this order:

1. **Spec updates**
   - define execution ordering modes
   - define partition-key semantics
   - define ordered-claim guarantees

2. **Store model and SPI**
   - add durable partition metadata
   - extend claim API with explicit policy
   - update in-memory store semantics first

3. **Runtime observability**
   - expand inspection API
   - add topology and runtime snapshots

4. **Runtime execution changes**
   - compute partition metadata on ingress
   - pass claim policy through workers
   - implement bucket-aware scheduling

5. **Postgres store**
   - implement indexed ordered/unordered claims
   - add efficient retry/lease reclaim queries

6. **Notification optimization**
   - add optional `LISTEN/NOTIFY` wake-ups after correctness and metrics are in place

---

## 14. Open Design Questions

These questions remain intentionally open for the future implementation:

- should ordering be pipeline-wide, step-specific, or both?
- should `step_sequence` be global per run or per step?
- how much snapshot data should be derived in SQL vs assembled in runtime memory?
- should the first inspection API be library-only or also immediately exposed through a CLI?
- when ordered mode is enabled, should retries block the partition strictly until terminal outcome, or should some retry states be bypassable?

The default bias should be toward:

- minimal guarantees
- explicit opt-in for stricter ordering
- store-enforced correctness
- operational clarity over abstraction sophistication

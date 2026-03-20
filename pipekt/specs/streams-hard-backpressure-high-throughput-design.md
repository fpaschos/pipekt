# Streams Hard Backpressure High Throughput Design

**Status:** Active technical design document.

**Purpose:** Define the target hard backpressure model for the durable Postgres-backed runtime, explain the core concepts behind admission control versus source throttling, and describe the data and transaction shape required for ultra-high-throughput ingestion without violating `maxInFlight`.

**Precedence:** This document does not override [streams-contracts-v1.md](./streams-contracts-v1.md) for stable runtime contracts. It refines the intended implementation of `maxInFlight` and should be read after [streams-contracts-v1.md](./streams-contracts-v1.md), [current-implementation.md](./current-implementation.md), and [streams-technical-requirements.md](./streams-technical-requirements.md). If it conflicts with the current implementation, [current-implementation.md](./current-implementation.md) still describes what exists today.

## Purpose and scope

The current runtime implements `maxInFlight` as a soft throttle:

1. observe current non-terminal work
2. decide whether there appears to be room
3. append ingress later

That pattern is a time-of-check/time-of-use race. It is acceptable as an interim throttle, but it does not provide a hard admission guarantee.

This document defines the harder model needed for serious throughput and durability:

- the active working set must stay bounded by store-enforced admission
- admission must be atomic with capacity accounting
- `countNonTerminal` must become an observability query, not the admission authority
- burst buffering must be separated from active execution ownership

This is a design document for the Postgres-backed runtime path. It does not require the in-memory store to implement the same internal mechanics.

---

## Core concepts

### Source pressure vs admission pressure vs execution pressure

These are different controls and should not be conflated.

- **Source pressure:** the system slows or pauses polling, acking, or intake from AMQP, Kafka, HTTP, or another external source.
- **Admission pressure:** the durable store decides whether more work may enter the active working set.
- **Execution pressure:** workers are slow because of CPU, IO, lease, DB, concurrency, or downstream limits.

Hard backpressure in this runtime means **hard admission pressure**, not merely slower polling.

### Active set

The active set is the number of non-terminal items currently owned by the runtime for a run.

For v1 this means items in non-terminal states such as:

- `PENDING`
- `IN_PROGRESS`

Terminal states such as `COMPLETED`, `FAILED`, and `FILTERED` do not consume active capacity after their terminal checkpoint commits.

### Soft throttle vs hard cap

**Soft throttle**

- the runtime checks current occupancy
- the runtime later inserts work
- concurrent writers can admit more work between those two actions
- `maxInFlight` may be exceeded temporarily

**Hard cap**

- the store owns the authoritative capacity state
- admission and capacity accounting happen in the same transaction
- no separate check-then-insert race exists
- `nonTerminalCount <= maxInFlight` remains true at commit boundaries

### Buffer vs active ownership

At high throughput, the system should separate:

- **ingress buffer:** what has arrived durably
- **active work items:** what the runtime is currently allowed to own and execute
- **capacity state:** how much active work is still allowed

These are related, but they should not be collapsed into one hot table or one overloaded concept.

---

## Design goals

The target design should satisfy all of the following:

- enforce `maxInFlight` as a true hard cap for the active set
- avoid per-admission `COUNT(*)` scans on hot `work_items`
- support large bursty ingress without unbounded active-row growth
- keep claim and checkpoint paths simple and index-friendly
- preserve restart safety and lease-based recovery
- allow the source layer to pause, defer, or buffer when the active set is full

---

## Non-goals

This design does not attempt to introduce:

- true reactive-streams semantics
- exactly-once distributed processing
- event-time windows
- joins, DAGs, or graph scheduling
- a public optimizer or physical planner API

The goal is bounded durable execution for the existing linear v1 runtime model.

---

## Why `countNonTerminal` is insufficient

A store query like:

```sql
SELECT count(*)
FROM work_items
WHERE run_id = $1
  AND state IN ('PENDING', 'IN_PROGRESS');
```

is not enough for hard backpressure.

Problems:

- it is a separate observation, not an admission decision
- another transaction can insert before the current writer appends
- aggregate scans against a hot execution table become expensive under sustained load

`countNonTerminal` should remain available for observability, health reporting, and debugging, but not as the authoritative admission gate.

---

## Authoritative admission state

The durable store should hold a small run-scoped capacity record that is the authority for admission.

Illustrative schema:

```sql
CREATE TABLE run_capacity (
  run_id text PRIMARY KEY,
  max_in_flight integer NOT NULL,
  non_terminal_count integer NOT NULL DEFAULT 0,
  CHECK (max_in_flight > 0),
  CHECK (non_terminal_count >= 0),
  CHECK (non_terminal_count <= max_in_flight)
);
```

Conceptually:

- `max_in_flight` is the configured hard limit for the run
- `non_terminal_count` is the current occupied active capacity
- this row is locked during admission changes

This is the durable "turnstile counter" for the run.

---

## Admission transaction model

### Rule

Admission must be atomic:

1. lock the run's capacity row
2. compute available capacity
3. admit only up to that amount
4. increment active occupancy in the same transaction

### Illustrative flow

Given:

- `max_in_flight = 100000`
- `non_terminal_count = 99980`
- incoming batch size = 100

Only 20 items may be admitted.

Illustrative transaction shape:

```sql
BEGIN;

SELECT max_in_flight, non_terminal_count
FROM run_capacity
WHERE run_id = $1
FOR UPDATE;
```

Compute:

```text
available = max_in_flight - non_terminal_count
accepted = min(batch_size, available)
```

Insert only the accepted subset into `work_items`, then:

```sql
UPDATE run_capacity
SET non_terminal_count = non_terminal_count + $accepted
WHERE run_id = $1;

COMMIT;
```

The store must return how many records were accepted and how many were deferred or rejected.

### Store API implication

The Postgres-backed store should evolve toward an admission API that is atomic by construction, for example:

```kotlin
suspend fun appendIngressWithCap(
    runId: String,
    maxInFlight: Int,
    batch: List<IngressRecord>,
): AppendIngressResult
```

Where `AppendIngressResult` should at minimum expose:

- accepted count
- deferred or rejected count
- optionally admitted IDs or accepted keys

The exact shape can vary, but the key property must remain: callers cannot separately "check" and then "append".

---

## When capacity is released

Capacity is released only when an item leaves the active set.

### Terminal transition

If an item becomes:

- `COMPLETED`
- `FAILED`
- `FILTERED`

then the same checkpoint transaction should decrement `non_terminal_count` by 1.

### Step advance

If an item moves from one step to another non-terminal step:

- the item is still active
- `non_terminal_count` does not change

This distinction is important. Advancing from step A to step B is progress, but not capacity release.

---

## Ingress buffer for burst absorption

Hard backpressure does not require an ingress buffer, but ultra-high-throughput systems usually benefit from one.

Without a separate buffer:

- the hot `work_items` table absorbs source bursts directly
- active execution state and burst storage become the same concern
- the execution table grows noisier and more expensive under spikes

With a separate buffer:

- source writes durably into an append-oriented buffer
- an admission loop drains only what fits into active capacity
- `work_items` remains the bounded active execution set

Illustrative shape:

```text
source -> ingress buffer -> admission gate -> work_items -> workers
```

This yields a cleaner separation:

- ingress buffer answers "what has arrived?"
- `work_items` answers "what is currently owned by the runtime?"
- `run_capacity` answers "how much more active work is allowed?"

### Illustrative schema

```sql
CREATE TABLE ingress_buffer (
  ingress_id bigserial PRIMARY KEY,
  run_id text NOT NULL,
  payload_json jsonb NOT NULL,
  item_key text,
  created_at timestamptz NOT NULL DEFAULT now(),
  admitted_at timestamptz
);
```

The exact schema can differ. The important point is the conceptual separation, not this exact column set.

---

## Active execution table

`work_items` remains the runtime's execution-state table, not the admission authority.

Illustrative responsibilities:

- current step ownership
- retry and attempt state
- lease owner and expiry
- payload and checkpoint state
- final terminal status

The table may still contain recently terminal items until archival retention removes them, but the hard cap applies only to the non-terminal subset.

---

## Admission loop with a buffer

When a buffer exists, the admission loop should:

1. lock `run_capacity` for the target run
2. compute available active capacity
3. select at most that many unadmitted buffered rows
4. insert corresponding `work_items`
5. mark buffered rows as admitted
6. increment `non_terminal_count`
7. commit

Illustrative query pattern:

```sql
BEGIN;

SELECT max_in_flight, non_terminal_count
FROM run_capacity
WHERE run_id = $1
FOR UPDATE;
```

Then:

```sql
WITH to_admit AS (
  SELECT ingress_id, payload_json, item_key
  FROM ingress_buffer
  WHERE run_id = $1
    AND admitted_at IS NULL
  ORDER BY ingress_id
  LIMIT $available
  FOR UPDATE SKIP LOCKED
)
INSERT INTO work_items (run_id, step_name, state, payload_json)
SELECT $1, $first_step, 'PENDING', payload_json
FROM to_admit;
```

Then:

```sql
UPDATE ingress_buffer
SET admitted_at = now()
WHERE ingress_id IN (...selected ids...);

UPDATE run_capacity
SET non_terminal_count = non_terminal_count + $accepted
WHERE run_id = $1;

COMMIT;
```

This is illustrative SQL, not frozen final syntax.

---

## Why `FOR UPDATE` matters

`FOR UPDATE` serializes capacity changes for a run.

Without it:

- multiple ingestors can read the same capacity snapshot
- each believes there is room
- each inserts
- the cap is violated

With it:

- one transaction owns the capacity decision for that run at a time
- other transactions wait or take other work
- the active count stays correct

This is the simplest correct pattern for hard admission.

---

## Throughput scaling strategy

The run-scoped capacity row is the correctness anchor, but it can also become a hot row if too many writers contend on the same run. The scaling plan should therefore be incremental.

### Stage 1 — single admission owner per run

Prefer one admission loop or actor per pipeline run.

This preserves simple correctness and avoids many concurrent writers fighting over the same capacity row.

### Stage 2 — larger admission batches

Admit work in batches rather than per-item transactions.

Fewer capacity transactions usually matter more than micro-optimizing individual inserts.

### Stage 3 — push-driven worker wakeup

Use `LISTEN/NOTIFY`, pgmq, or a similar queue-assisted signal so workers do not poll empty tables aggressively.

This does not create hard backpressure by itself, but it reduces idle database pressure significantly.

### Stage 4 — narrow claim paths

At higher scale, use:

- strong indexes on claimable subsets
- claim-by-ID paths when a queue provides concrete candidate item IDs
- step-aware claim paths rather than broad scans

### Stage 5 — buffer and active-table partitioning

When row volume becomes large, partition `ingress_buffer` and `work_items` by a practical operational dimension such as pipeline family, run family, or time, depending on the actual workload.

Partitioning is an optimization layer, not the first correctness mechanism.

### Stage 6 — reserved token schemes only if necessary

Advanced token reservation can reduce lock frequency by reserving capacity in chunks. However, it complicates failure handling because reserved-but-not-materialized slots must be tracked carefully.

Do not start here. Only add reservation after simpler batch admission has been measured and shown insufficient.

---

## Policy when capacity is full

A hard cap requires an explicit full-capacity policy.

Valid strategies:

- **pause polling:** stop pulling from the source until active capacity frees up
- **buffer durably:** continue accepting ingress into `ingress_buffer`, but do not admit it into active execution
- **reject ingress:** fail fast and rely on upstream retry or drop policy

For ultra-high-throughput continuous sources, the preferred approach is usually:

- bounded active execution via `run_capacity`
- durable burst absorption via `ingress_buffer`

This keeps the active set controlled without forcing the source edge to be perfectly smooth.

---

## Recommended contract direction for `pipekt`

The following direction fits the current runtime and spec set:

1. freeze `maxInFlight` as a store-enforced hard admission cap for the durable Postgres runtime
2. keep `countNonTerminal` for observability, not admission control
3. add run-scoped durable capacity state
4. decrement capacity only on terminal checkpoint
5. treat step advancement as non-terminal occupancy with no capacity change
6. support a separate ingress buffer for bursty or externally paced sources
7. keep the in-memory store simpler if needed, but document that its behavior is not the performance reference implementation

---

## Relationship to existing documents

- [streams-contracts-v1.md](./streams-contracts-v1.md) defines the public `maxInFlight` concept and the current admission responsibility.
- [current-implementation.md](./current-implementation.md) documents that the runtime currently implements soft backpressure.
- [streams-technical-requirements.md](./streams-technical-requirements.md) covers operational tuning, `LISTEN/NOTIFY`, pgmq options, and database pressure considerations.
- [streams-phase-2-fix-plan.md](./streams-phase-2-fix-plan.md) tracks the still-open work to freeze and implement the chosen backpressure semantics.

This document narrows the intended durable-store answer: hard admission must be store-owned and atomic.

---

## Summary

For this runtime, hard backpressure does not mean "poll more slowly." It means:

- the active non-terminal set is explicitly bounded
- admission is atomic with capacity accounting
- the store, not the runtime loop, is the authority for whether more work fits
- throughput scaling comes from batching, buffer separation, narrow claim paths, and push-driven wakeups

That is the difference between a best-effort throttle and a durable hard cap suitable for ultra-high-throughput execution.

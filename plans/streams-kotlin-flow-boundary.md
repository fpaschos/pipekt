# Streams Kotlin Flow Boundary

## Summary

Kotlin Flow is a memory-centric stream abstraction: values flow through connected operators in the same process with no persistence. The `streams` engine is store-centric: Postgres is the coordination medium between independent worker loops. These are fundamentally different models. Flow cannot be the pipeline model because it provides no durability, no lease ownership, and no crash recovery — the three properties that distinguish this engine from an in-memory reactive library.

---

## Why Flow Cannot Be the Core Pipeline Model

**1. No durability boundary.**
If the process crashes while an item is inside a Flow chain (after `step1` emits, before `step2` collects), the item is gone. The engine's value is that `checkpointSuccess` writes to Postgres before the item advances — Flow has no equivalent.

**2. Wrong backpressure mechanism.**
Flow suspends coroutines when a downstream operator is slow (`buffer()`, `conflate()`, `collectLatest()`). The engine's backpressure is a Postgres row count: `countNonTerminal(runId) >= maxInFlight` → pause ingestion. These two mechanisms conflict and cannot be composed.

**3. No lease model.**
Flow passes values by reference in memory. There is no atomic `claim` with `leaseOwner` and `leaseExpiryMs`, and no watchdog that resets `IN_PROGRESS` items when a worker crashes. Flow has no crash recovery concept.

**4. Independent loops, not a connected pipeline.**
The step-2 worker loop does not receive items from the step-1 worker loop. It independently queries Postgres for `current_step = 'step2' AND status = 'PENDING'`. Workers do not know about each other. This is the opposite of a connected Flow chain where operators are wired together at construction time.

---

## Where Flow Is Used

| Layer | Use Flow? | Reason |
| --- | --- | --- |
| `pipekt.core` contracts | No | Contracts must be transport and runtime agnostic |
| `pipekt.store` | No | Store is the source of truth; no stream abstraction needed |
| `pipekt.runtime` worker loops | No | Independent `while (isActive)` loops reading from store |
| `pipekt.runtime` ingestion loop | No | `countNonTerminal` check must happen before every poll; not expressible as a Flow operator chain |
| `pipekt.adapters.amqp` (internal) | Yes — Phase 4 | AMQP channel consumption is naturally a `Flow`; used as an internal implementation detail only; the public `SourceAdapter` contract stays as `poll/ack/nack` lists |
| Phase 6 metrics / `pipekit tail` | Yes — Phase 6 | `SharedFlow` / `StateFlow` for hot event broadcasting to health endpoint, Micrometer, and CLI |
| Phase 6 watchdog ticker | Yes — Phase 6 | Ticker flow is a natural fit for the periodic `reclaimExpiredLeases` call |

---

## Rule

Public contracts in `pipekt.core`, `pipekt.store`, `pipekt.runtime`, and `pipekt.adapters` must not reference any Kotlin Flow type (`Flow`, `SharedFlow`, `StateFlow`, `Channel`, or any `kotlinx.coroutines.flow` type).

Flow is an implementation detail of specific adapter and framework integration layers, never a public engine contract.

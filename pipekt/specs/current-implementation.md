# Current Implementation

**Status:** Current implementation snapshot.

**Purpose:** Record what exists in code today so architecture and planning docs are not misread as implemented behavior.

**Precedence:** This document is secondary to [streams-contracts-v1.md](./streams-contracts-v1.md) for stable contracts, but it wins over architecture/planning docs when the question is "what is implemented right now?"

## Implemented

- `pipekt.core`
  - pipeline DSL and validation
  - `StepFn` is `suspend`
  - `PayloadSerializer` plus `KotlinxPayloadSerializer`
- `pipekt.store`
  - `DurableStore` SPI
  - `RunRecord`, `WorkItem`, `AppendIngressResult`
  - `InMemoryStore`
- `pipekt.runtime.new`
  - actor-backed `PipelineOrchestrator`
  - `PipelineExecutable`
  - `PipelineDefinition.start(planVersion, config)`
  - `PipelineRuntimeActor`
  - `IngressWorkerActor`
  - `StepWorkerActor`
  - store-scoped `LeaseReclaimerActor`

## Current Runtime Semantics

- ingestion, per-step work, and lease reclaim are owned by separate actors
- runtime orchestration is actor-first; registry/lifecycle mutation is not shared mutable state
- payloads are serialized into `payloadJson`, overwritten at each successful non-terminal checkpoint, and nulled at terminal checkpoint
- terminal payload nulling is both a correctness invariant and a storage/performance rule:
  - completed, filtered, or terminally failed items must not retain large historical payload JSON
  - this bounds retained payload size and reduces long-lived row bloat in durable storage
- current backpressure behavior is **soft**
  - the runtime checks `countNonTerminal(runId)` and then later appends ingress
  - this is a best-effort throttle in the current implementation, not a store-enforced hard admission cap

## Intentionally Missing

- durable Postgres store
- framework-specific lifecycle/composition layer
- production source adapters
- per-step concurrency controls beyond the current worker layout

## Known Open Gaps

See [streams-phase-2-fix-plan.md](./streams-phase-2-fix-plan.md) for the authoritative list. The highest-signal open items are:

- `lastErrorJson` end-to-end persistence/alignment
- step-local `attemptCount` semantics
- startup reclaim-before-workers
- loop resilience on infra failures
- explicit freezing of backpressure semantics
- clearer orchestrator ownership documentation

# Streams Loyalty Reference Flow

## Summary

This document defines the loyalty workflow as the reference flow that validates the generic `streams` API.

It exists to prove the engine design, not to specialize the engine around the loyalty domain.

The loyalty reference flow is an `INFINITE` pipeline — continuous ingress from AMQP with no batch boundary, no barrier, and no finalizer. Items are individually terminal; the run is a long-lived grouping key that never finalizes.

---

## Business Flow To Model

The reference loyalty flow is:

1. read policy-related records from AMQP continuously
2. check sequence-id eligibility via REST
3. fetch policy details via REST
4. persist per-policy checkpoint state
5. execute a second external phase one policy at a time

---

## Pipeline Execution Mode

The loyalty reference flow is an **INFINITE** pipeline:

- continuous ingress from AMQP, no batch boundary
- no barrier, no finalizer
- one long-lived `runId` across restarts
- items are individually terminal; the run is never finalized

---

## Generic Operator Mapping

| Loyalty phase | Generic operator | Scope |
| --- | --- | --- |
| AMQP continuous ingress | `source` | run ingress |
| sequence-id eligibility | `filter` | item |
| download policy details | `step` | item |
| persist details checkpoint | `persistEach` | item |
| phase-2 external call | `step` with `concurrency = 1` | item |

---

## Reference Pipeline Shape

```kotlin
pipeline<PolicyMessage>("loyalty-policy-sync") {
  source(amqpSource())

  filter<PolicyMessage, LoyaltyError>("seq-id-eligible") { msg ->
    eligibilityApi.isAllowed(msg.sequenceId)
  }

  step<PolicyMessage, PolicyDetails, LoyaltyError>("download-policy-details") { msg ->
    detailsApi.fetch(msg.policyNumber)
  }

  persistEach("details-checkpoint")

  step<PolicyDetails, Phase2Result, LoyaltyError>(
    "phase2-one-by-one",
    concurrency = 1,
  ) { details ->
    phase2Api.send(details)
  }
}
```

This is illustrative. The core design requirement is the operator behavior, not this exact syntax.

---

## Why This Flow Is The Right Reference

This workflow stresses the engine in the places that matter:

- continuous ingress from a real transport
- filter vs failure distinction
- typed payload transformation between steps
- durable per-item checkpoint after enrichment
- forced sequential execution for one phase
- per-item terminal completion with immediate payload compaction

---

## Engine Stress Points

### Continuous Ingress

The engine must support:

- the ingestion loop running independently from worker loops
- items flowing through steps as they arrive, without a batch boundary
- the run never transitioning to a terminal state

### Per-Item Filtering And Enrichment

The engine must distinguish:

- item filtered out by business rule
- item retried because of temporary downstream failure
- item failed terminally because the business flow cannot proceed

### Checkpoint Persistence

The engine must support an explicit persistence boundary after policy details are fetched so that:

- restart can continue from phase 2
- the details-fetch side effect is not repeated unnecessarily

### Payload Compaction

At terminal checkpoint (`COMPLETED`, `FILTERED`, `FAILED`), `payload_json` must be nulled immediately inside the checkpoint transaction. The Phase 5 acceptance tests must verify this invariant.

### Sequential Phase

The engine must enforce `concurrency = 1` for the second external phase, independent of upstream throughput.

### Restart And Reclaim

On restart, the engine must:

- locate the existing long-lived `runId` for this pipeline via `getOrCreateRun`
- call `reclaimExpiredLeases` before re-launching worker loops
- resume from durable store state with no in-memory coordination

---

## Domain Types The Example Needs

The loyalty example should introduce concrete types similar to:

- `PolicyMessage`
- `PolicyDetails`
- `Phase2Result`
- `LoyaltyError`

These types live in the loyalty example package, not in core contracts.

---

## What The Reference Flow Must Not Do

- define AMQP-specific fields in core step signatures
- require kt-framework types in step code
- assume the current `ampq` package structure
- force the engine to be loyalty-domain-specific
- use `barrier` or `finalizeRun` operators

---

## Acceptance Criteria

The reference flow is good enough when it proves:

- the generic source contract can support continuous AMQP ingestion
- the generic runtime can support mixed parallel and sequential item phases
- durable checkpoints are sufficient for restart from the middle
- INFINITE mode pipelines run continuously without unbounded storage growth
- `payload_json` is null for all terminal items after checkpoint
- per-item completion, filtering, and failure all work correctly
- the engine resumes correctly after a simulated crash using only store state

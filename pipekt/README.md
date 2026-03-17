# PipeKt Specs

## Current MVP Baseline

These documents are the active source of truth for the current `pipekt` core module and its stream-processing runtime:

- [streams-contracts-v1.md](./specs/streams-contracts-v1.md)
- [current-implementation.md](./specs/current-implementation.md)
- [streams-core-architecture.md](./specs/streams-core-architecture.md)
- [streams-delivery-phases.md](./specs/streams-delivery-phases.md)
- [streams-technical-requirements.md](./specs/streams-technical-requirements.md)
- [streams-phase-2-fix-plan.md](./specs/streams-phase-2-fix-plan.md)
- [streams-example-earthquake-enrichment.md](./specs/streams-example-earthquake-enrichment.md)

Read them in that order.

Precedence:

1. [streams-contracts-v1.md](./specs/streams-contracts-v1.md) wins for stable public/runtime-store contracts.
2. [current-implementation.md](./specs/current-implementation.md) wins for what exists in code today.
3. [streams-core-architecture.md](./specs/streams-core-architecture.md) wins for intended package boundaries and ownership model.
4. [streams-delivery-phases.md](./specs/streams-delivery-phases.md) wins for sequencing and milestone intent.
5. [streams-technical-requirements.md](./specs/streams-technical-requirements.md) wins for operational guidance and tuning.
6. [streams-phase-2-fix-plan.md](./specs/streams-phase-2-fix-plan.md) wins for unresolved correctness gaps that must be closed before Phase 3.

Reading notes:

- `streams-technical-requirements.md` covers orthogonal runtime concerns (default values, error handling, DB performance, pipeline registry, and code organization).
- `streams-phase-2-fix-plan.md` is the concrete remediation checklist for the current runtime/store drift, now including the remaining `runtime.new` findings and the already-addressed v2 architecture reference.
- `streams-example-earthquake-enrichment.md` is an illustrative example, not a higher-precedence contract document.

## Runtime-Local Notes

- [actor-based-runtime.md](./src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/new/actor-based-runtime.md) - actor ownership model for the `runtime.new` rewrite

## Folder Layout

### Top level

Active MVP specifications and implementation guidance only.

### `future/`

Ideas intentionally outside the current MVP:

- [datafusion-inspired-durable-streaming-design.md](./specs/future/datafusion-inspired-durable-streaming-design.md)
- [streams-future-planning-features.md](./specs/future/streams-future-planning-features.md)

## Current Direction

- Build the new library under `io.github.fpaschos.pipekt`
- V1 supports **INFINITE pipelines only** — continuous ingress, no batch boundary, no barrier, no finalizer
- `BOUNDED` mode (barrier, finalizer, batch ingress) is explicitly deferred to a future version
- Treat earlier transport-specific experiments as historical reference only
- Keep a minimal logical definition to executable runtime split in MVP
- Defer framework-specific integration until after the core engine and runtime contracts are validated

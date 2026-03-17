# PipeKt Specs

## Current MVP Baseline

These documents are the active source of truth for the current `pipekt` core module and its stream-processing runtime:

- [streams-current-state-and-legacy-boundary.md](./specs/streams-current-state-and-legacy-boundary.md)
- [streams-core-architecture.md](./specs/streams-core-architecture.md)
- [streams-contracts-v1.md](./specs/streams-contracts-v1.md)
- [streams-delivery-phases.md](./specs/streams-delivery-phases.md)
- [streams-delivery-additions.md](./specs/streams-delivery-additions.md)
- [streams-technical-requirements.md](./specs/streams-technical-requirements.md)
- [streams-phase-2-fix-plan.md](./specs/streams-phase-2-fix-plan.md)
- [streams-example-earthquake-enrichment.md](./specs/streams-example-earthquake-enrichment.md)

Read them in that order. `streams-delivery-additions.md` supplements `streams-delivery-phases.md` with critical fixes, architecture decisions, and the Postgres schema reference; read it alongside or immediately after the phases doc. `streams-technical-requirements.md` covers orthogonal runtime concerns (default values, error handling, DB performance, pipeline registry, and code organization); read it before implementing Phase 2 or application wiring. `streams-phase-2-fix-plan.md` is the concrete remediation checklist for the current runtime/store drift, now including the remaining `runtime.new` findings and the already-addressed v2 architecture reference; read it before declaring Phase 2 complete or starting Phase 3.

## Runtime-Local Notes

- [actor-based-runtime.md](./src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/new/actor-based-runtime.md) - actor ownership model for the `runtime.new` rewrite

## Folder Layout

### Top level

Active MVP planning documents only.

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

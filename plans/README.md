# Sapp Loyalty Plans

## Current MVP Baseline

These documents are the active source of truth for the new parallel `streams` library:

- [streams-current-state-and-legacy-boundary.md](plans/streams-current-state-and-legacy-boundary.md)
- [streams-core-architecture.md](plans/streams-core-architecture.md)
- [streams-contracts-v1.md](plans/streams-contracts-v1.md)
- [streams-loyalty-reference-flow.md](plans/streams-loyalty-reference-flow.md)
- [streams-delivery-phases.md](plans/streams-delivery-phases.md)

Read them in that order.

## Folder Layout

### Top level

Active MVP planning documents only.

### `future/`

Ideas intentionally outside the current MVP:

- [datafusion-inspired-durable-streaming-design.md](plans/future/datafusion-inspired-durable-streaming-design.md)
- [streams-future-planning-features.md](plans/future/streams-future-planning-features.md)

### `archive/`

Historical plans that are no longer the active baseline.

Keep them only for context. Do not use them as the implementation source of truth.

- [durable-pipeline-implementation-plan.md](plans/archive/durable-pipeline-implementation-plan.md)
- [mvp-experiment-implementation-plan.md](plans/archive/mvp-experiment-implementation-plan.md)

## Current Direction

- Build the new library under `gr.pipekt.streams`
- V1 supports **INFINITE pipelines only** — continuous ingress, no batch boundary, no barrier, no finalizer
- `BOUNDED` mode (barrier, finalizer, batch ingress) is explicitly deferred to a future version
- Treat current `ampq` code as legacy reference only
- Keep a minimal logical definition to executable runtime split in MVP
- Defer kt-framework integration until after the core engine and loyalty reference flow are validated

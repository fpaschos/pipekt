# Streams Current State And Legacy Boundary

## Summary

This document defines the boundary between:

- the current `pipekt` implementation that exists in this module today
- earlier transport-specific or pre-orchestrator ideas that should not redefine the current architecture

The current code in `core`, `runtime/new`, and `store` is the implementation baseline. Older planning language or removed transport experiments are reference material only.

## Current Repo State

The current `pipekt` module contains:

- pipeline definition and validation code under `io.github.fpaschos.pipekt.core`
- a store SPI and in-memory implementation under `io.github.fpaschos.pipekt.store`
- the actor-backed runtime under `io.github.fpaschos.pipekt.runtime.new`
- runtime architecture notes in `src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/new/actor-based-runtime.md`
- active specs under `pipekt/specs`

What is not present yet:

- framework-specific integration layers
- a Postgres-backed store implementation
- non-in-memory durable store implementations
- production adapters such as AMQP/Kafka source integrations
- example applications built on top of the core runtime

## What The Legacy Implementation Actually Does

The current implementation is already a stream-processing library with:

It currently provides:

- a typed pipeline DSL and validation layer
- a store-backed runtime with run and work-item state
- backpressure based on `maxInFlight`
- retry and filter semantics for step execution
- an actor-based orchestrator and runtime ownership model
- an in-memory `DurableStore` for tests and development

It does not provide:

- a production durable database implementation
- framework lifecycle integration
- production-grade source adapters
- bounded/barrier/finalizer orchestration
- application examples that validate the engine end to end

## Legacy-Only Concerns

The following are historical concerns and must not shape the current public API:

- transport-specific runtime ownership
- direct coupling between source transport and business workflow
- framework-specific DI assumptions
- non-store-backed processing loops
- any design that bypasses the orchestrator as the owner of active runtimes

## Useful Reference To Keep

The following may inform the new design without being copied directly:

- transport constraints:
  - bounded buffers are required
  - source adapters need explicit ack/nack semantics
  - broker clients often have thread-affinity or connection ownership constraints
- failure handling ideas:
  - backpressure by refusing unbounded buffering
  - transport-specific retry inputs can map into engine retry policy
  - explicit ack/nack timing still matters at adapter boundaries

These are adapter concerns, not core engine concerns.

## New Streams Boundary

The new library starts under:

- `io.github.fpaschos.pipekt.core`
- `io.github.fpaschos.pipekt.runtime.new`
- `io.github.fpaschos.pipekt.store`
- `io.github.fpaschos.pipekt.adapters.amqp`
- `io.github.fpaschos.pipekt.examples.loyalty`

Rules for this boundary:

1. `pipekt.core` must not depend on kt framework.
2. `pipekt.runtime.new` must remain framework-agnostic.
3. adapters may depend on JVM-only infrastructure.
4. loyalty example code validates the generic API, but does not define it.
5. adapters must plug into the runtime through `SourceAdapter` and store/runtime contracts rather than redefining them.

## KT Framework Position

The current module intentionally stops at framework-agnostic engine/runtime code; that is not the same thing as service-level integration.

For planning purposes:

- kt framework is absent from the new library design baseline
- this is intentional
- integration will be added later as a composition layer after core contracts are stable

This prevents framework lifecycle, DI, tracing, and request-context concerns from distorting the engine contracts.

## Build Vs Keep Vs Defer

| Concern | Status | Decision |
| --- | --- | --- |
| Generic pipeline DSL | Exists | Keep evolving in `pipekt.core` |
| Durable runtime | Exists | Keep evolving in `pipekt.runtime.new` |
| Durable store SPI | Exists | Keep evolving in `pipekt.store` |
| In-memory durable store | Exists | Keep for tests and development |
| Postgres store | Missing | Defer to delivery phase 3 |
| AMQP source adapter for new engine | Missing | Build after core/store contracts |
| Example workflow on generic engine | Missing | Build after runtime and store contracts |
| KT framework integration | Missing | Defer until after loyalty example validates contracts |

## Consequences For Implementation

- The current work should continue to define engine contracts first, not transport or framework behavior.
- No public type in `pipekt.core` may depend on framework or application wiring.
- AMQP work must happen behind a source adapter boundary.
- Any transport integration should adapt to the existing core/runtime/store contracts rather than replacing them.

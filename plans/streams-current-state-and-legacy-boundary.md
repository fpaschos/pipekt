# Streams Current State And Legacy Boundary

## Summary

This document defines the boundary between:

- the current `sapp-loyalty` implementation, which is legacy reference only
- the new `gr.hd360.sapp.loyalty.streams` library, which will be built in parallel

The current code must not be used as the architectural baseline for the new library. It is useful only as a transport-behavior reference.

## Current Repo State

The current `sapp-loyalty` module contains:

- application bootstrap and Koin wiring in `Application.kt`
- a `LoyaltyConsumer` and `LoyaltyScheduler`
- a transport-focused `ampq` package with:
  - `AbstractAmpqHandler`
  - `RabbitConnections`
  - `ChannelPool`
  - topology specs and message envelope helpers
- a RabbitMQ design note in `ampq/RABBITMQ.md`
- planning documents for a larger durable pipeline library under `sapp-loyalty/plans`

What is not present yet:

- a generic pipeline DSL under a `streams` package
- a durable runtime that owns run/item/attempt state
- a generic durable store SPI
- a Postgres-backed store implementation
- a source adapter SPI that AMQP plugs into
- a loyalty reference workflow implemented on top of generic pipeline operators
- kt-framework-aware integration for the new library

## What The Legacy Implementation Actually Does

The current implementation is a RabbitMQ transport slice, not a stream-processing library.

It currently provides:

- exchange and queue declaration
- publish support with a bounded publisher channel pool
- a sequential consumer loop
- bounded in-memory buffering based on prefetch
- simple retry behavior based on delivery count
- DLQ routing behavior
- request-id propagation and `ExecContext` reconstruction

It does not provide:

- durable work tracking across restarts
- store-owned progress and recovery
- a reusable pipeline definition model
- barrier/finalizer orchestration at run scope
- engine-level backpressure across multiple stages
- framework-neutral contracts

## Legacy-Only Concerns

The following are legacy-specific and must not shape the new `streams` public API:

- `ampq` package structure
- `AbstractAmpqHandler<T>` as the primary abstraction
- direct coupling between transport handler and business workflow
- startup from `init` blocks
- Koin-specific creation assumptions
- direct use of `ExecContext` in core library contracts
- current scheduler-driven tick example as an engine model

## Useful Reference To Keep

The following may inform the new design without being copied directly:

- RabbitMQ topology concepts:
  - exchange spec
  - queue spec
  - dead-letter routing
- transport constraints:
  - channels are not thread-safe
  - bounded buffers are required
  - publish and consume connections should be isolated
- failure handling ideas:
  - redelivery count as transport input
  - backpressure by refusing unbounded buffering
  - explicit ack/nack timing

These are adapter concerns, not core engine concerns.

## New Streams Boundary

The new library starts under:

- `gr.hd360.sapp.loyalty.streams.core`
- `gr.hd360.sapp.loyalty.streams.runtime`
- `gr.hd360.sapp.loyalty.streams.store`
- `gr.hd360.sapp.loyalty.streams.adapters.amqp`
- `gr.hd360.sapp.loyalty.streams.examples.loyalty`

Rules for this boundary:

1. `streams.core` must not depend on kt framework.
2. `streams.runtime` must remain framework-agnostic.
3. adapters may depend on JVM-only infrastructure.
4. loyalty example code validates the generic API, but does not define it.
5. legacy `ampq` code remains untouched until the new library is ready for integration.

## KT Framework Position

The old implementation reconstructs and passes `ExecContext`, but this is not enough to call it kt-framework integration for the new design.

For planning purposes:

- kt framework is absent from the new library design baseline
- this is intentional
- integration will be added later as a composition layer after core contracts are stable

This prevents framework lifecycle, DI, tracing, and request-context concerns from distorting the engine contracts.

## Build Vs Keep Vs Defer

| Concern | Status | Decision |
| --- | --- | --- |
| RabbitMQ topology knowledge | Exists in legacy code | Keep as adapter reference |
| Publisher channel pooling | Exists in legacy code | Keep as adapter design input |
| Sequential consumer loop | Exists in legacy code | Keep as transport behavior reference |
| Generic pipeline DSL | Missing | Build in `streams.core` |
| Durable runtime | Missing | Build in `streams.runtime` |
| Durable store SPI | Missing | Build in `streams.store` |
| Postgres store | Missing | Defer to delivery phase 3 |
| AMQP source adapter for new engine | Missing | Build after core/store contracts |
| Loyalty workflow on generic engine | Missing | Build after runtime and store contracts |
| KT framework integration | Missing | Defer until after loyalty example validates contracts |

## Consequences For Implementation

- The first code written under `streams` must define engine contracts, not RabbitMQ behavior.
- No public type in `streams.core` may depend on `ExecContext`, Koin, or current application wiring.
- AMQP work must happen behind a source adapter boundary.
- Any migration from legacy code happens only after the new core runtime and loyalty example exist.

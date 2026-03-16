# PipeKt

`pipekt` is a Kotlin Multiplatform work-in-progress library for building long-running data pipelines and streaming-style processing flows.

The project is currently focused on a small core:

- a typed pipeline definition DSL
- a runtime/orchestration layer for starting and managing pipeline runs
- actor-based concurrency primitives
- a durable store contract for resumable work and checkpointing

## What It Tries To Accomplish

PipeKt aims to provide a practical foundation for:

- defining pipelines in Kotlin with strong typing and validation
- running continuous, infinite pipelines with backpressure-aware execution
- persisting pipeline state so work can survive failures and resume safely
- evolving toward a reusable stream-processing/runtime library rather than app-specific glue code

## Current Status

This project is a work in progress.

The architecture, contracts, and runtime are still being shaped, and parts of the implementation are actively being rewritten. The `plans/` directory is the current source of truth for the MVP direction and near-term design decisions.

For now, PipeKt should be treated as an experimental project, not a stable production-ready library.

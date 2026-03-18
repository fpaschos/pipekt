# Actor-based runtime

**Purpose:** Define the runtime ownership model for the new actor-based implementation under `io.github.fpaschos.pipekt.runtime.new`.

**Status:** Working architecture note.

**Scope:** This document defines actor boundaries, ownership, and terminology only. It does not define the full execution algorithm, storage schema, or worker internals.

**Related:** [streams-delivery-phases.md](../../../../../../../../../specs/streams-delivery-phases.md), [streams-technical-requirements.md](../../../../../../../../../specs/streams-technical-requirements.md), [streams-phase-2-fix-plan.md](../../../../../../../../../specs/streams-phase-2-fix-plan.md)

---

## 1. Core terms

### `PipelineOrchestrator`

Process-level coordinator.

Responsibilities:

- starts pipelines
- stops pipelines
- tracks active pipeline executables in memory
- answers questions about active pipelines
- owns store-scoped background coordination such as lease reclaim

The orchestrator is the entry point used by callers. It does not execute pipeline steps itself.

### `PipelineExecutable`

The user-facing returned object for a started pipeline.

This replaces the earlier `RuntimeHandle` naming.

Meaning:

- when a pipeline is started, the caller gets back a `PipelineExecutable`
- `PipelineExecutable` represents one running in-memory executable pipeline instance
- it is the capability through which the caller interacts with that running pipeline

`PipelineExecutable` is not the process-wide orchestrator. It refers to one active runtime created by the orchestrator.

### `PipelineRuntime`

The actual runtime engine for one started pipeline.

Responsibilities:

- owns the execution of one `PipelineDefinition`
- reads and writes work through the durable store
- coordinates source ingestion and step progression
- owns worker actors for the executable operators of that pipeline
- manages pipeline-local lifecycle

This is the in-memory thing that actually runs the pipeline.

---

## 2. Target start API

The intended API is:

```kotlin
context(orchestrator: PipelineOrchestrator)
suspend fun PipelineDefinition.start(
    planVersion: String,
    config: RuntimeConfig = RuntimeConfig(),
): PipelineExecutable =
    orchestrator.startPipeline(
        definition = this,
        planVersion = planVersion,
        config = config,
    )
```

Interpretation:

- the orchestrator is the authority that starts pipelines
- the returned value is `PipelineExecutable`
- the executable represents one running pipeline runtime

---

## 3. Actor rule

An actor should exist only where we need a serialized owner of mutable coordination state.

Use an actor when a component:

- owns lifecycle
- owns child actors or background loops
- serializes commands such as start, stop, pause, resume, or inspect
- manages mutable in-memory state that must not be concurrently mutated

Do not use an actor for:

- immutable definitions
- compiled plan data
- serializers
- store implementations
- business step functions
- simple value objects or snapshots

This keeps actors for coordination, not for everything.

---

## 4. What is an actor

### 4.1 `PipelineOrchestratorActor`

This should be an actor.

Why:

- it owns the map of active pipelines
- `startPipeline` and `stopPipeline` must be serialized
- it is the single authority for active runtime registration
- it can own the store-scoped watchdog actor

Owns:

- `Map<PipelineExecutableId, PipelineRuntimeActorRef>` or equivalent
- active executable metadata
- process-level shutdown coordination

Commands it will eventually handle:

- start pipeline
- stop pipeline
- stop all pipelines
- list active pipelines
- inspect active pipeline

### 4.2 `PipelineRuntimeActor`

This should be an actor.

Why:

- it owns one running pipeline instance
- it owns runtime lifecycle for that pipeline
- it owns the child workers
- runtime start/stop/drain/inspection must be serialized

Owns:

- pipeline-local runtime state
- run identity
- compiled executable plan
- worker refs
- ingress ownership
- shutdown state

This actor coordinates execution. It should not become the place where all hot-path step logic is manually inlined.

### 4.3 `StepWorkerActor`

This should be an actor.

There is one worker actor per executable operator role that needs its own loop and state.

For the current design, that means:

- source-side ingestion worker when source polling is runtime-owned
- step worker for each step operator
- filter worker for each filter operator

Why:

- each worker owns one polling/execution loop
- each worker has its own backoff, polling cadence, and local lifecycle
- each worker can be started, stopped, and observed independently

The current legacy `PipelineRuntime` already points in this direction conceptually: each definition node results in runtime-owned worker behavior. The new design keeps that idea but gives each worker a clear actor owner.

### 4.4 `LeaseReclaimerActor`

This should be an actor.

Why:

- lease reclaim is store-scoped, not per-runtime
- there should be one serialized owner of the reclaim loop
- the orchestrator should own it

There must not be one watchdog per pipeline runtime.

---

## 5. What is not an actor

### 5.1 `PipelineDefinition`

Not an actor.

It is an immutable logical definition supplied by the caller.

### 5.2 Executable plan data

Not an actor.

Examples:

- resolved operator ordering
- next-step lookup
- step metadata
- serializer/type metadata

These are plain data used by the runtime actor and worker actors.

### 5.3 `PipelineExecutable`

Not an actor.

`PipelineExecutable` is the user-facing capability returned from `startPipeline(...)`.

It should be a small object that points at the owning actor boundary, most likely the orchestrator and/or runtime actor.

It may expose operations such as:

- `stop()`
- `snapshot()`
- `pipelineName`
- `runId`

But it does not own mutable execution state itself.

### 5.4 Step functions and filter functions

Not actors.

They are execution logic invoked by workers.

### 5.5 Store and serializer infrastructure

Not actors.

Examples:

- `DurableStore`
- `PayloadSerializer`
- clocks
- id generators

These are services used by actors.

### 5.6 Work items

Not actors.

Item state belongs in the durable store. We do not create one actor per item.

---

## 6. Ownership model

The ownership graph should be:

```text
PipelineOrchestratorActor
  -> LeaseReclaimerActor
  -> PipelineRuntimeActor (one per started pipeline)
       -> IngressWorkerActor (optional, if source-owned)
       -> StepWorkerActor (one per executable step/filter role)
```

Meaning:

- the orchestrator owns all active pipeline runtimes
- each runtime owns its workers
- a worker never outlives its runtime
- the lease reclaimer is not owned by a runtime

---

## 7. Responsibilities by layer

### Orchestrator layer

Owns:

- process-wide pipeline registry
- start and stop commands
- lookup and inspection entry points
- store-scoped reclaim loop

Does not own:

- step execution
- per-item processing
- store row mutation logic itself

### Runtime layer

Owns:

- one started pipeline
- runtime lifecycle
- worker creation and worker supervision policy
- pipeline-local state and plan

Does not own:

- the global pipeline registry
- store-scoped reclaim policy

### Worker layer

Owns:

- one operator loop
- claim/poll/execute/checkpoint cadence for its operator role
- local execution backoff and stop/drain state

Does not own:

- pipeline registration
- cross-pipeline coordination

---

## 8. Design decisions

### 8.1 `PipelineExecutable` is the returned handle

The returned object from `PipelineDefinition.start(...)` is `PipelineExecutable`, not `RuntimeHandle`.

Reason:

- the user starts a pipeline and gets back the executable for that started pipeline
- this is clearer than a generic handle name
- it matches the idea that the orchestrator creates and holds active executables

### 8.2 Actors are for coordination, not algorithm payload

The actor architecture is used to control runtime ownership and concurrency boundaries.

It is not a goal to force every execution concern into message passing.

The hot-path algorithm still lives in the runtime and worker implementations. Actors provide safe ownership around that logic.

### 8.3 Existing runtime code is reference only

The current runtime and actor implementation should be treated as design reference only.

The new implementation under `runtime.new` is a fresh architecture and may rename, split, or discard existing types freely.

---

## 9. Initial target package model

The new work should start under:

```text
io.github.fpaschos.pipekt.runtime.new
```

Expected high-level types:

- `PipelineOrchestrator`
- `PipelineExecutable`
- `PipelineRuntime`
- `PipelineOrchestratorActor`
- `PipelineRuntimeActor`
- `StepWorkerActor`
- `LeaseReclaimerActor`

Exact naming can still change, but these are the current architectural roles.

---

## 10. Summary

The actor boundaries are:

- orchestrator actor for process-wide pipeline coordination
- runtime actor for one running pipeline
- worker actors for operator-local loops
- one store-scoped lease reclaimer actor

The non-actor boundaries are:

- `PipelineDefinition`
- executable plan data
- `PipelineExecutable`
- step logic
- store and serializer services
- individual work items

The user starts a pipeline through the orchestrator and gets back a `PipelineExecutable`. The orchestrator owns active executables. Each executable is powered by one runtime actor, and each runtime actor owns the workers that actually move items through the pipeline.

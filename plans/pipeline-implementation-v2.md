# Pipeline implementation v2 — plan and findings

**Purpose:** Plan for refactoring the pipeline runtime around an **orchestrator**, clearer ownership, universal watchdog, and DSL ergonomics. This document captures findings from design discussion and will guide implementation.

**Status:** Planning. Not yet implemented.

**Related:** [streams-contracts-v1.md](streams-contracts-v1.md), [streams-technical-requirements.md](streams-technical-requirements.md), [streams-delivery-phases.md](streams-delivery-phases.md).

---

## 1. Findings summary

### 1.1 Config at start (done)

- **RuntimeConfig** is a data class holding `workerPollInterval`, `watchdogInterval`, `leaseDuration`, `workerClaimLimit` with the same defaults as before.
- Passed to **`PipelineRuntime.start(config: RuntimeConfig = RuntimeConfig())`**, not to the constructor.
- Constructor keeps only identity/wiring: `pipeline`, `store`, `serializer`, `scope`, `planVersion`.

### 1.2 Plan version ownership

- **Who defines plan version:** The **launcher** (whoever says “run this pipeline”) — e.g. app bootstrap, PipelineManager, or test. The pipeline author can suggest a value; the launcher supplies it.
- **Where it lives:** On the **`PipelineRuntime` constructor** (`planVersion: String`). Do not move to `RuntimeConfig` (that is tuning, not run identity). Optional: allow `PipelineDefinition` to carry a `planVersionHint` for convenience; the launcher still passes the authoritative value.

### 1.3 Who creates the runtime (per plans)

- According to the plans, a **PipelineManager** (or equivalent) in the application/framework layer creates and holds runtimes, with `startPipeline(definition, serializer, planVersion)`.
- **v2 decision:** This “orchestrator” should live **inside this library** (see §2). The library then provides the component that materializes and holds active pipelines; any framework (Ktor, Spring, kt-framework, CLI) uses the same abstraction.

### 1.4 Framework-agnostic library

- The library has no dependency on kt-framework, Koin, Ktor, or Spring. “Phase 6: KT Framework Integration” in the plans means one reference implementation in the kt stack, not a library requirement.
- The **orchestrator** (see §2) is a library type; the app supplies store, serializer, and scope (or the orchestrator creates a scope). Works with any web or non-web framework.

### 1.5 Watchdog should be universal

- **Problem:** `DurableStore.reclaimExpiredLeases(now, limit)` operates on the **entire store** (all pipelines, all runs). Today each `PipelineRuntime` runs its **own** watchdog loop, so N runtimes ⇒ N redundant loops calling the same store-wide operation.
- **Direction:** Reclaim is a **store-level** concern. One of:
  - **A)** A single **store-scoped watchdog** (e.g. `LeaseReclaimer` or run by the orchestrator) started **once per store**, not per runtime.
  - **B)** The **store implementation** runs the watchdog internally (e.g. background job).
- **PipelineRuntime** should **not** own a watchdog loop. Remove it from the runtime; document that the orchestrator (or the store) runs one periodic `reclaimExpiredLeases` for the store.

### 1.6 Initialization and self-containment

- **Problem:** Initialization is unclear — who creates store, scope, runtime, and in what order. The runtime is a “worker” with many injected dependencies; no single entry point that clearly owns “run this pipeline.”
- **Direction:** The **orchestrator** is that entry point. Caller gives a pipeline (and planVersion/config); orchestrator materializes the runtime, starts it, and returns a handle. The orchestrator owns lifecycle and concurrency (see §2).

### 1.7 Concurrency issues (current PipelineRuntime)

- **Unprotected `jobs` list:** Mutable list modified in `start()` and read/cleared in `stop()`. If `start()`/`stop()` can be called from different coroutines, concurrent modification is possible. Fix: guard with `Mutex` or move all start/stop into a single owner (the orchestrator).
- **`runId` visibility:** `lateinit var runId` written in `start()` and read by launched coroutines on possibly different threads. Ensure safe publication (e.g. volatile or set before any launch).
- **TOCTOU in ingestion:** `countNonTerminal` then `appendIngress` is a time-of-check-time-of-use race; backpressure is soft. Document or tighten with store support if needed.
- **Orchestrator as actor** (see §2) centralizes start/stop/hold state so the runtime’s internal concurrency surface can be reduced or simplified.

### 1.8 Orchestrator: missing component

- **Gap:** There is no single component that you “give a pipeline” and get back a “runtime handle.” The plans describe a PipelineManager above the runtime but place it in the app layer; ownership and concurrency are then ad hoc.
- **Proposal:** Add an **orchestrator** (e.g. `PipelineOrchestrator` or `PipelineManager`) that:
  - You call with “run this pipeline” (definition + planVersion + config).
  - It **creates** the `PipelineRuntime`, starts it, and returns a **handle** (e.g. `RuntimeHandle` with `stop()`, maybe `runId` or status).
  - It **holds** active pipelines (e.g. map of name or handle → runtime + job).
  - It is implemented as a **self-contained actor**: one coroutine, one channel (or mutex), so all operations (start, stop, list) are serialized. No shared mutable state visible to callers; the actor owns the map and lifecycle.
  - It runs **one** store-level watchdog (one loop for the store), so the runtime no longer owns a watchdog.
- This orchestrator **belongs in this library**: it only needs `PipelineDefinition`, `DurableStore`, `PayloadSerializer`, `RuntimeConfig`, and a scope. It materializes and holds active pipelines for the environment; every consumer uses the same type.

### 1.9 DSL ergonomics: receiver for “pipeline start”

- **Goal:** From the DSL, express “pipeline start” naturally.
- **Chosen approach: receiver.** Use a receiver so that inside a block the pipeline definition can call `.start(...)` and the orchestrator is in scope, e.g.:
  ```kotlin
  with(orchestrator) {
      pipeline<Msg>("my-pipe", maxInFlight = 100) {
          source("src", adapter)
          step<Msg, Msg>("step1") { it }
      }.start(planVersion = "v1")  // extension on PipelineDefinition; uses receiver orchestrator
  }
  ```
  Or the orchestrator exposes a scope in which `pipeline { ... }.start(...)` resolves the orchestrator from the receiver.
- **Alternative (not chosen for v2):** `orchestrator.pipeline<Msg>("name") { ... }` that both builds and starts and returns a handle — possible later if we want “start” fully implicit.

### 1.10 Who creates the orchestrator

- **Who:** The **application** (or a framework-specific piece that has store, serializer, and scope). That is the same role as the current "launcher" that creates the runtime: in v2 the launcher creates the **orchestrator** instead, and the orchestrator creates runtimes when `startPipeline(...)` is called.
- **Where:** At **bootstrap** (main, entry point, or framework wiring). Create **one orchestrator per environment (e.g. per process)** and use it for all pipeline starts.
- **Typical owners:** App bootstrap, Ktor plugin, Spring bean factory, CLI main, or test setup. The library does not create the orchestrator automatically; the app supplies store, serializer, and scope (see §1.4).
- The orchestrator is the single entry point that owns runtimes and the store-level watchdog for that environment.

---

## 2. Orchestrator design (target)

- **Type:** e.g. `PipelineOrchestrator` in `pipekt.runtime` (or `pipekt.orchestrator` if we introduce a package).
- **Construction:** Takes **store**, **serializer**, and **scope** (or creates and owns a scope). Optionally takes **RuntimeConfig** defaults or a **watchdog** config (interval, limit).
- **Behaviour:**
  - **startPipeline(definition, planVersion, config): RuntimeHandle** — creates `PipelineRuntime(definition, store, serializer, scope, planVersion)`, calls `runtime.start(config)`, registers the runtime (and its jobs) in the actor’s map, returns a handle. No per-runtime watchdog; runtime has only ingestion + workers.
  - **stop(handle)** or **handle.stop()** — sends stop to the actor; actor calls `runtime.stop()`, removes from map.
  - **listActive()** or **handles()** — returns current handles or pipeline names (from the actor’s map).
  - **Watchdog:** One loop (either inside the orchestrator or a small companion) that periodically calls `store.reclaimExpiredLeases(now, limit)` for the **store**. Started when the orchestrator starts; stopped when the orchestrator stops.
- **Concurrency:** All mutations (map of runtimes, start/stop) go through a single serialized point (actor coroutine or mutex). Handles are opaque identifiers; only the orchestrator touches the runtime and job list.

---

## 3. Runtime handle

- **RuntimeHandle** (or similar): Opaque or minimal type returned by `startPipeline(...)`.
  - **stop()** — request the orchestrator to stop this pipeline (suspend until stopped).
  - Optionally: **runId**, **pipelineName**, or **status** for observability.
  - No direct access to `PipelineRuntime`; the handle is the only reference the caller holds.

---

## 4. PipelineRuntime changes (v2)

- **Remove** the watchdog loop from `PipelineRuntime`. Document that reclaim is the orchestrator’s (or store’s) responsibility.
- **Keep** ingestion loop and worker loops in `PipelineRuntime`. Runtime remains the execution engine; orchestrator owns lifecycle and the set of active runtimes.
- **Optional:** Simplify internal concurrency (e.g. protect `jobs` with a mutex, or document that only the orchestrator may call start/stop so that single-threaded use is guaranteed). Prefer orchestrator as the single caller of start/stop so that the runtime’s concurrency surface is minimal.

---

## 5. DSL integration

- **Extension or method:** `PipelineDefinition.start(planVersion, config): RuntimeHandle` in a context where the **receiver** is the orchestrator (e.g. `with(orchestrator) { definition.start(...) }`), or an extension that takes the orchestrator as first parameter and is used as `definition.start(orchestrator, planVersion, config)`.
  - If receiver-based: define an interface or scope type (e.g. `PipelineOrchestratorScope`) that provides the orchestrator, and an extension `PipelineDefinition.start(planVersion, config)` that uses the receiver to call `orchestrator.startPipeline(this, planVersion, config)`.
- **Documentation:** Show the recommended pattern: create one orchestrator per environment (e.g. per process), then use `with(orchestrator) { pipeline { ... }.start(...) }` (or equivalent) so that “pipeline start” lives in the DSL.

---

## 6. Implementation order (suggested)

1. **Document and refactor watchdog:** Remove watchdog loop from `PipelineRuntime`; document that the caller (orchestrator) must run one store-level `reclaimExpiredLeases` loop. Tests: ensure one external watchdog is started (e.g. by the test) so that reclaim still happens.
2. **Introduce orchestrator and handle:** Add `PipelineOrchestrator` (or chosen name) and `RuntimeHandle` in the library. Implement as actor (or mutex-protected map). Orchestrator starts one store-level watchdog. `startPipeline(definition, planVersion, config)` creates runtime (without watchdog), starts it, returns handle; `stop(handle)` stops and removes.
3. **Migrate tests:** PipelineRuntimeHappyPathTest (and others) create an orchestrator, call `orchestrator.startPipeline(definition, ...)` (or equivalent), and use the handle to stop. No direct `PipelineRuntime` construction in tests (or keep a few unit tests that still use the runtime directly for execution-only tests).
4. **DSL receiver and `.start()`:** Add the receiver-based API so that `with(orchestrator) { pipeline { ... }.start(planVersion = "v1") }` (or equivalent) works. Document in user-facing docs and in this plan.
5. **Optional:** Harden `PipelineRuntime` concurrency (mutex on `jobs`, safe publication of `runId`) if we ever allow multiple callers of start/stop; or leave as “single caller = orchestrator” and document.

---

## 7. Open points

- **Naming:** `PipelineOrchestrator` vs `PipelineManager` vs other. Plans use “PipelineManager”; “Orchestrator” was used in discussion to stress lifecycle and actor behaviour.
- **Scope ownership:** Does the orchestrator take a scope from the app or create its own (e.g. `CoroutineScope` + `Job` that it cancels on shutdown)? Taking a scope keeps the library flexible; creating one simplifies shutdown (cancel orchestrator’s job → all runtimes and watchdog stop).
- **Watchdog as separate type:** Optional small type `LeaseReclaimer(store, interval, limit)` that the orchestrator starts, vs. the orchestrator just launching one loop. Either way, one loop per store.

---

*Document created from design discussion; to be updated as implementation progresses.*

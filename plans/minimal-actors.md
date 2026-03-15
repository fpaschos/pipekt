# Minimal actors for PipeKt runtime

**Purpose:** Define one canonical actor implementation model for `pipekt.runtime`, including lifecycle, startup, shutdown, bounded mailbox behavior, command failure handling, `ActorRef`, request/reply helpers, and actor-specific side jobs. `PipelineOrchestrator` is used as a worked example, not as the only target actor.

**Status:** Planning. Intended to drive implementation directly.

**Related:** [pipeline-implementation-v2.md](pipeline-implementation-v2.md), [streams-technical-requirements.md](streams-technical-requirements.md), [streams-core-architecture.md](streams-core-architecture.md).

---

## 1. Scope

This document standardizes the minimal actor infrastructure used inside `pipekt.runtime`.

It applies to any actor-like entity inside `pipekt.runtime`, including:

- common mailbox-driven runtime components
- `PipelineOrchestrator`
- `PipelineRuntimeV2`
- future actor-like runtime coordinators

It does not introduce a public actor framework. The base `Actor` remains an internal runtime primitive.

---

## 2. Goals

- Remove repeated actor bootstrapping code from runtime classes.
- Remove public `start()` methods whose only purpose is to launch the mailbox loop.
- Separate actor infrastructure from domain commands.
- Give each actor a clear external handle (`ActorRef` or domain-specific ref).
- Support actor-specific side jobs such as watchdogs, pollers, reapers, and child cleanup.
- Support bounded mailbox behavior and explicit rejection semantics.
- Support bounded shutdown semantics instead of treating shutdown as a normal mailbox command.
- Make startup, shutdown, and failure behavior explicit.

---

## 3. Non-goals

- No Akka-like supervision tree API in v1.
- No automatic actor restart in place.
- No priority mailbox in v1.
- No public generic actor DSL.
- No public raw message endpoint where callers can send arbitrary mailbox commands.

---

## 4. Core design

The canonical actor model has three layers:

1. `Actor<Command>`
   - Internal shared infrastructure.
   - Owns mailbox, loop job, startup barrier, termination barrier, lifecycle state, command delivery helpers, and shutdown behavior.
   - Uses a lifecycle-only `Mutex` to coordinate startup/shutdown transitions, not normal command handling.

2. Concrete actor implementation
   - Defines a sealed command protocol `Command`.
   - Implements `handle(command)`.
   - Optionally starts and stops actor-specific side jobs via `postStart()` and `preStop()`.

3. `ActorRef`
   - Narrow external API returned by `spawn(...)`.
   - Implemented as an abstract base ref, not an interface.
   - Exposes business operations plus shutdown.
   - Does not expose mailbox, jobs, or mutable internal state.

Construction is standardized:

- actor constructors are `private` or `internal`
- `spawn(...)` creates the actor
- `spawn(...)` waits until startup succeeds
- `spawn(...)` returns the ref, not the raw actor

The command loop is not started from `init`, and not exposed as a separate public lifecycle concern.

---

## 5. Actor lifecycle

### 5.1 States

All actors use the same infrastructure lifecycle:

```kotlin
internal enum class ActorLifecycle {
    STARTING,
    RUNNING,
    SHUTTING_DOWN,
    SHUTDOWN,
}
```

Meaning:

- `STARTING`: actor exists but `postStart()` has not completed successfully yet
- `RUNNING`: accepts new commands
- `SHUTTING_DOWN`: no new commands accepted; actor is draining or being cancelled
- `SHUTDOWN`: loop terminated and cleanup completed

### 5.2 Domain lifecycle vs actor lifecycle

Actor lifecycle is infrastructure-level and separate from domain state.

Example actor-specific domain states may include:

- `PipelineRuntimeV2` may still keep `PipelineLifecycle.NEW/RUNNING/SHUTDOWN` for the pipeline run itself
- another coordinator actor may keep its own `IDLE/ACTIVE/FAILED` domain lifecycle
- `ActorLifecycle` only answers whether the mailbox host is alive and accepting commands

Do not collapse those concepts into a single enum.

---

## 6. Base `Actor` contract

File target:

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/Actor.kt`

Reference implementation:

```kotlin
package io.github.fpaschos.pipekt.runtime

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

internal enum class ActorLifecycle {
    STARTING,
    RUNNING,
    SHUTTING_DOWN,
    SHUTDOWN,
}

internal class ActorMailboxClosedException(
    actorName: String,
) : IllegalStateException("$actorName is not accepting new commands")

internal class ActorMailboxFullException(
    actorName: String,
) : IllegalStateException("$actorName mailbox is full")

internal abstract class Actor<Command : Any>(
    private val scope: CoroutineScope,
    private val actorName: String,
    capacity: Int = Channel.BUFFERED,
) {
    protected val mailbox = Channel<Command>(capacity)

    private val lifecycleMutex = Mutex()
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()

    private val lifecycle = atomic(ActorLifecycle.STARTING)

    private val loopJob: Job =
        scope.launch(CoroutineName(actorName)) {
            try {
                postStart()

                lifecycleMutex.withLock {
                    if (lifecycle.value != ActorLifecycle.STARTING) {
                        if (!started.isCompleted) {
                            started.completeExceptionally(
                                CancellationException("$actorName was stopped during startup"),
                            )
                        }
                        return@launch
                    }

                    lifecycle.value = ActorLifecycle.RUNNING
                    started.complete(Unit)
                }

                for (command in mailbox) {
                    try {
                        handle(command)
                    } catch (t: Throwable) {
                        onUnhandledCommandFailure(command, t)
                    }
                }
            } catch (t: Throwable) {
                if (!started.isCompleted) {
                    started.completeExceptionally(t)
                }
                throw t
            } finally {
                lifecycle.value = ActorLifecycle.SHUTDOWN
                terminated.complete(Unit)
            }
        }

    suspend fun awaitStarted() {
        started.await()
    }

    suspend fun awaitTerminated() {
        terminated.await()
    }

    protected abstract suspend fun handle(command: Command)

    protected open suspend fun postStart() {}

    protected open suspend fun preStop() {}

    protected open suspend fun onUnhandledCommandFailure(
        command: Command,
        cause: Throwable,
    ) {
        // Default behavior is non-fatal. Concrete actors should complete replies exceptionally
        // or record the failure through actor-specific observability.
    }

    protected fun ensureAccepting() {
        check(lifecycle.value == ActorLifecycle.RUNNING) {
            "$actorName is not accepting new commands: ${lifecycle.value}"
        }
    }

    protected fun trySend(command: Command): ChannelResult<Unit> {
        return when (lifecycle.value) {
            ActorLifecycle.RUNNING -> mailbox.trySend(command)
            ActorLifecycle.STARTING,
            ActorLifecycle.SHUTTING_DOWN,
            ActorLifecycle.SHUTDOWN,
            -> ChannelResult.closed(ActorMailboxClosedException(actorName))
        }
    }

    protected fun send(command: Command) {
        val result = trySend(command)
        result.getOrElse { cause ->
            throw cause ?: ActorMailboxFullException(actorName)
        }
    }

    protected suspend fun <R> request(
        build: (CompletableDeferred<R>) -> Command,
    ): R {
        ensureAccepting()
        val reply = CompletableDeferred<R>()
        val result = trySend(build(reply))
        result.getOrElse { cause ->
            throw cause ?: ActorMailboxFullException(actorName)
        }
        return reply.await()
    }

    protected suspend fun shutdownGracefully(timeout: Duration? = null) {
        val shouldStop =
            lifecycleMutex.withLock {
                when (lifecycle.value) {
                    ActorLifecycle.STARTING,
                    ActorLifecycle.RUNNING,
                    -> {
                        lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                        true
                    }
                    ActorLifecycle.SHUTTING_DOWN,
                    ActorLifecycle.SHUTDOWN,
                    -> false
                }
            }

        if (shouldStop) {
            mailbox.close()
            preStop()
        }

        if (timeout == null) {
            terminated.await()
            return
        }

        val completed = withTimeoutOrNull(timeout) {
            terminated.await()
            true
        } == true

        if (!completed) {
            loopJob.cancel()
            terminated.await()
        }
    }

    protected suspend fun shutdownNow() {
        val shouldStop =
            lifecycleMutex.withLock {
                when (lifecycle.value) {
                    ActorLifecycle.STARTING,
                    ActorLifecycle.RUNNING,
                    -> {
                        lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                        true
                    }
                    ActorLifecycle.SHUTTING_DOWN,
                    ActorLifecycle.SHUTDOWN,
                    -> false
                }
            }

        if (shouldStop) {
            mailbox.close()
            preStop()
        }

        loopJob.cancel()
        terminated.await()
    }
}
```

### 6.1 Required behavior

- The mailbox loop is created exactly once when the actor is instantiated.
- No nullable `commandsLoop`.
- Startup is acknowledged via `awaitStarted()`.
- Termination is acknowledged via `awaitTerminated()`.
- `send(...)` is the standard one-way internal command helper.
- `request(...)` is the standard internal request/reply helper.
- New requests are rejected once shutdown begins.
- Mailbox delivery is bounded and fail-fast by default.
- The `Mutex` is used only for lifecycle transitions and single-flight shutdown, never for normal command handling.
- Lifecycle visibility is modeled with multiplatform atomics rather than JVM-only `@Volatile`.

### 6.2 Hook semantics

- `postStart()`
  - Called from the actor loop coroutine before the mailbox drain loop begins.
  - Use for actor-specific side jobs owned by the actor.
  - Examples: a watchdog, periodic reconciliation, child registry sync, metrics sampler.

- `preStop()`
  - Called when shutdown begins.
  - Called after the mailbox is closed to new sends.
  - Use for stopping side jobs and actor-owned resources while the actor is terminating.
  - Examples: cancelling pollers, stopping child actors, releasing owned scopes.

- `postStop()`
  - Not part of the minimal base actor contract in v1.
  - Add only if a concrete actor needs a true post-termination hook.

### 6.3 Why `postStart()` instead of `init`

- avoids `this` escaping from constructor logic
- keeps startup tied to actor lifetime rather than object allocation
- makes startup failure visible to `spawn(...)`
- allows `spawn(...)` to be the only creation path

### 6.4 Why the `Mutex` exists

The base actor keeps a `Mutex` for lifecycle transitions only.

It is needed because:

- startup completion and external shutdown can race
- multiple callers may try to shut the actor down concurrently
- the mailbox loop alone does not serialize those external lifecycle transitions

It is intentionally not used for:

- protecting actor business state
- wrapping `handle(command)`
- wrapping long-running suspend cleanup

This keeps the hot command path mailbox-serialized and lock-free, while making startup and shutdown transitions sound.

### 6.5 Why atomic lifecycle state exists

The base actor uses an atomic lifecycle state because this code is Kotlin Multiplatform.

Why not `@Volatile`:

- `@Volatile` is JVM-oriented and not the right common-model tool for KMP actor infrastructure
- actor lifecycle is read from multiple coroutines outside the lifecycle mutex

Why atomic state is used:

- it gives multiplatform-safe visibility for read-mostly lifecycle checks
- it keeps the fast path cheap for `ensureAccepting()` and `trySend()`
- it works together with the `Mutex`, which still owns transition sequencing

---

## 7. External access model

External callers interact only through typed refs.

They do not:

- call generic `send(...)`
- call generic `request(...)`
- send raw mailbox commands
- inspect mailbox state

This is intentional. Public callers address an actor only through the capabilities exposed by its ref.

Example:

- `PipelineOrchestratorRef` exposes `startPipeline(...)`, `stopPipeline(...)`, `listPipelines()`, and `shutdown(...)`
- `PipelineOrchestrator` may internally hold many `RuntimeRef`s
- external code still never sees raw runtime mailbox commands

This differs from public `tell`-style actor APIs by design. For this library, typed capability refs are safer than exposing raw message transport.

---

## 8. Failure handling

### 8.1 Command failure

Default rule:

- one failing command must not kill the actor unless the actor explicitly chooses that behavior

This is why each mailbox dispatch is wrapped in its own `try/catch`.

Recommended concrete rule:

- for reply-bearing commands, complete the reply exceptionally
- for one-way commands, record or propagate failure in an actor-specific way
- infrastructure failures in `postStart()` or in the actor loop itself may still terminate the actor

`onUnhandledCommandFailure(...)` exists so each actor can decide how to surface a command-level failure. The base default is non-fatal.

### 8.2 Startup failure

If `postStart()` fails:

- actor startup fails
- `awaitStarted()` fails
- `spawn(...)` must fail
- no ref should escape representing a half-started actor

This is why `spawn(...)` should be `suspend` by default.

### 8.3 Restart strategy

Automatic restart is out of scope in v1.

Rule:

- actors are one-shot
- once terminated, create a new actor via `spawn(...)`
- restart policy belongs above the actor layer, typically in application bootstrap or a higher-level coordinator

This avoids hidden restart semantics around side jobs, child refs, and domain state.

---

## 9. Mailbox behavior

### 9.1 Bounded mailbox

The mailbox is finite and fail-fast by default.

Why:

- suspending indefinitely in public API methods is a poor default
- unbounded growth hides overload and creates memory risk

Default behavior:

- `trySend(...)` is used internally
- if the mailbox is full, actor methods fail immediately
- if the actor is not accepting commands, actor methods fail immediately

### 9.2 Rejection semantics

Standard internal exceptions:

- `ActorMailboxFullException`
- `ActorMailboxClosedException`

Concrete refs may translate these into domain-specific errors if needed, but the base actor behavior should stay consistent.

### 9.3 No public ask/tell

There is no public generic equivalent of Akka `ask` or `tell`.

Mapping in this design:

- internal `send(...)` is the equivalent of `tell`
- internal `request(...)` is the equivalent of `ask`

But both belong to the actor implementation, not to the public ref contract.

The mailbox helpers use verified coroutines APIs:

- `Channel.trySend(...)`
- `ChannelResult.closed(cause)`
- `ChannelResult.getOrElse { cause -> ... }`

---

## 10. Shutdown semantics

Shutdown is not modeled as a normal mailbox command.

Reason:

- if shutdown is appended behind a very large queue, termination latency becomes unbounded
- runtime control must be able to stop acceptance of new work independently of user traffic

### 10.1 Graceful shutdown

Graceful shutdown means:

1. transition to `SHUTTING_DOWN`
2. stop accepting new commands
3. close the mailbox
4. run `preStop()`
5. allow already-buffered commands to drain
6. wait for termination

### 10.2 Forced shutdown

Forced shutdown means:

1. transition to `SHUTTING_DOWN`
2. close mailbox
3. run `preStop()`
4. cancel the loop job
5. wait for termination

### 10.3 Public shutdown contract

The default public contract should be:

- graceful with optional timeout
- if timeout expires, force cancel

### 10.4 Shutdown concurrency

Shutdown must be idempotent and single-flight.

Rules:

- the first shutdown caller initiates termination
- concurrent shutdown callers wait for the same termination result
- calls after `SHUTDOWN` return immediately

The `lifecycleMutex` plus `terminated` barrier in `Actor` provide this behavior.

The `Mutex` must guard only the state transition decision. It must not wrap `preStop()` or other long suspend work.

### 10.5 Observable guarantee

When a public `shutdown(...)` call returns:

- the actor loop has terminated
- actor-owned side jobs have been stopped
- no more commands will be processed

Always wait for termination before returning from public shutdown.

---

## 11. `ActorRef` contract

File target:

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/ActorRef.kt`

Reference implementation:

```kotlin
package io.github.fpaschos.pipekt.runtime

import kotlin.time.Duration

abstract class ActorRef {
    suspend fun shutdown(timeout: Duration? = null) {
        shutdownActor(timeout)
    }

    protected abstract suspend fun shutdownActor(timeout: Duration?)
}
```

Why an abstract class:

- ref and actor stay separate objects
- shutdown forwarding boilerplate is shared in one place
- concrete refs still define their own business operations

What does not belong on `ActorRef`:

- `request(...)`
- `send(...)`
- mailbox access
- lifecycle state inspection tied to actor internals

The ref is a capability wrapper, not a raw actor transport.

---

## 12. Spawn pattern

Every actor follows this construction rule:

```kotlin
companion object {
    suspend fun spawn(...): SomeActorRef {
        val actor = SomeActor(...)
        actor.awaitStarted()
        return SomeActorRef(actor)
    }
}
```

Rules:

- `spawn(...)` should be `suspend` by default
- `spawn(...)` must not return before startup succeeds
- public callers never invoke a separate public loop-boot `start()`
- public constructors should be avoided for actor implementations

---

## 13. Worked example: `PipelineOrchestrator`

File target:

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/PipelineOrchestrator.kt`

### 13.1 Responsibilities

`PipelineOrchestrator` is one concrete actor using this model. It:

- owns the map of active runtimes by pipeline name
- serializes pipeline start/stop/list operations
- owns one store-level watchdog loop
- creates runtime refs for started pipelines
- shuts down all active runtimes when the orchestrator stops

The orchestrator example uses two scopes:

- `actorScope`: runs the actor loop
- `childScope`: owns watchdog and child runtimes

This separation is required so actor-owned child cleanup does not cancel the actor loop out from under itself.

### 13.2 State model

Canonical minimal state:

```kotlin
private val runtimeByPipeline = mutableMapOf<String, RuntimeRef>()
private var watchdog: Job? = null
```

Do not keep competing maps unless the external API truly requires them.

### 13.3 Ref shape

```kotlin
package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PipelineDefinition
import kotlin.time.Duration

class PipelineOrchestratorRef internal constructor(
    private val actor: PipelineOrchestrator,
) : ActorRef() {
    suspend fun startPipeline(
        definition: PipelineDefinition,
        planVersion: String,
        config: RuntimeConfig = RuntimeConfig(),
    ): RuntimeRef =
        actor.startPipelineInternal(definition, planVersion, config)

    suspend fun stopPipeline(pipelineName: String) {
        actor.stopPipelineInternal(pipelineName)
    }

    suspend fun listPipelines(): Set<String> =
        actor.listPipelinesInternal()

    override suspend fun shutdownActor(timeout: Duration?) {
        actor.shutdownInternal(timeout)
    }
}
```

### 13.4 Command protocol

```kotlin
internal sealed interface Command {
    data class StartPipeline(
        val definition: PipelineDefinition,
        val planVersion: String,
        val config: RuntimeConfig,
        val reply: CompletableDeferred<RuntimeRef>,
    ) : Command

    data class StopPipelineByName(
        val pipelineName: String,
        val reply: CompletableDeferred<Unit>,
    ) : Command

    data class ListPipelines(
        val reply: CompletableDeferred<Set<String>>,
    ) : Command
}
```

### 13.5 Reference implementation

```kotlin
package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

internal class PipelineOrchestrator private constructor(
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
    private val config: OrchestratorConfig,
    private val actorScope: CoroutineScope,
    private val childScope: CoroutineScope,
    private val ownsChildScope: Boolean,
) : Actor<PipelineOrchestrator.Command>(
        scope = actorScope,
        actorName = "pipekt-orchestrator",
    ) {
    companion object {
        suspend fun spawn(
            store: DurableStore,
            serializer: PayloadSerializer,
            config: OrchestratorConfig = OrchestratorConfig(),
            externalScope: CoroutineScope? = null,
        ): PipelineOrchestratorRef {
            val actorScope =
                externalScope
                    ?: CoroutineScope(
                        SupervisorJob() + Dispatchers.Default + CoroutineName("pipekt-orchestrator-scope"),
                    )
            val ownsChildScope = externalScope == null
            val childScope =
                if (externalScope != null) {
                    externalScope
                } else {
                    CoroutineScope(
                        SupervisorJob() + Dispatchers.Default + CoroutineName("pipekt-orchestrator-children"),
                    )
                }

            val actor =
                PipelineOrchestrator(
                    store = store,
                    serializer = serializer,
                    config = config,
                    actorScope = actorScope,
                    childScope = childScope,
                    ownsChildScope = ownsChildScope,
                )

            actor.awaitStarted()
            return PipelineOrchestratorRef(actor)
        }
    }

    private val runtimeByPipeline = mutableMapOf<String, RuntimeRef>()
    private var watchdog: Job? = null

    override suspend fun postStart() {
        watchdog =
            childScope.launch(CoroutineName("pipekt-orchestrator-watchdog")) {
                while (isActive) {
                    delay(config.watchdogInterval)
                    store.reclaimExpiredLeases(
                        now = Clock.System.now(),
                        limit = config.reclaimLimit,
                    )
                }
            }
    }

    override suspend fun preStop() {
        watchdog?.cancel()
        watchdog?.join()
        watchdog = null

        val active = runtimeByPipeline.values.toList()
        runtimeByPipeline.clear()
        for (runtime in active) {
            runtime.shutdown()
        }

        if (ownsChildScope) {
            childScope.cancel()
        }
    }

    override suspend fun handle(command: Command) {
        when (command) {
            is Command.StartPipeline -> onStartPipeline(command)
            is Command.StopPipelineByName -> onStopPipelineByName(command)
            is Command.ListPipelines -> command.reply.complete(runtimeByPipeline.keys.toSet())
        }
    }

    override suspend fun onUnhandledCommandFailure(
        command: Command,
        cause: Throwable,
    ) {
        when (command) {
            is Command.StartPipeline -> command.reply.completeExceptionally(cause)
            is Command.StopPipelineByName -> command.reply.completeExceptionally(cause)
            is Command.ListPipelines -> command.reply.completeExceptionally(cause)
        }
    }

    suspend fun startPipelineInternal(
        definition: PipelineDefinition,
        planVersion: String,
        config: RuntimeConfig = RuntimeConfig(),
    ): RuntimeRef =
        request { reply ->
            Command.StartPipeline(
                definition = definition,
                planVersion = planVersion,
                config = config,
                reply = reply,
            )
        }

    suspend fun stopPipelineInternal(pipelineName: String) {
        request { reply -> Command.StopPipelineByName(pipelineName, reply) }
    }

    suspend fun listPipelinesInternal(): Set<String> =
        request { reply -> Command.ListPipelines(reply) }

    suspend fun shutdownInternal(timeout: Duration? = null) {
        shutdownGracefully(timeout)
    }

    private suspend fun onStartPipeline(command: Command.StartPipeline) {
        val existing = runtimeByPipeline[command.definition.name]
        if (existing != null) {
            command.reply.complete(existing)
            return
        }

        val runtime =
            PipelineRuntimeV2.spawn(
                pipeline = command.definition,
                planVersion = command.planVersion,
                deps =
                    RuntimeDeps(
                        store = store,
                        serializer = serializer,
                        scope = childScope,
                    ),
                config = command.config,
            )

        runtimeByPipeline[command.definition.name] = runtime
        command.reply.complete(runtime)
    }

    private suspend fun onStopPipelineByName(command: Command.StopPipelineByName) {
        val runtime = runtimeByPipeline.remove(command.pipelineName)
        runtime?.shutdown()
        command.reply.complete(Unit)
    }

    internal sealed interface Command {
        data class StartPipeline(
            val definition: PipelineDefinition,
            val planVersion: String,
            val config: RuntimeConfig,
            val reply: CompletableDeferred<RuntimeRef>,
        ) : Command

        data class StopPipelineByName(
            val pipelineName: String,
            val reply: CompletableDeferred<Unit>,
        ) : Command

        data class ListPipelines(
            val reply: CompletableDeferred<Set<String>>,
        ) : Command
    }
}
```

### 13.6 Notes

- the watchdog is actor-specific and belongs in `postStart()` / `preStop()`
- public callers still never send raw commands
- orchestrator shutdown is not queued behind normal workload
- the child scope is separate from the actor loop scope so shutdown does not self-cancel the actor

---

## 14. Second worked example: `PipelineRuntimeV2`

`PipelineRuntimeV2` should be migrated to the same model after the orchestrator.

Required direction:

- make constructor non-public
- expose `spawn(...)`
- remove public loop-boot `start()`
- keep only domain operations through `RuntimeRef`

Important distinction:

- loop startup is infrastructure and belongs to `Actor`
- starting a pipeline execution is a domain concern

If `PipelineRuntimeV2` still keeps a domain `StartRuntime` command, that is acceptable, but it must be handled by an already-running actor.

This section exists to show that the base `Actor` is not orchestrator-specific.

---

## 15. File-level implementation plan

### 15.1 Add

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/Actor.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/ActorRef.kt`

### 15.2 Refactor

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/PipelineOrchestrator.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/PipelineRuntimeV2.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/Types.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/PipelineDsl.kt`

### 15.3 Type changes

- add `PipelineOrchestratorRef`
- make `RuntimeRef` extend `ActorRef` if its semantics remain "shut down this runtime"
- move generic lifecycle helpers out of concrete actor files

---

## 16. Implementation order

1. Add `ActorRef.kt`.
2. Add `Actor.kt` with startup barrier, bounded mailbox behavior, and graceful or forced shutdown.
3. Refactor `PipelineOrchestrator` to extend `Actor`.
4. Introduce `PipelineOrchestratorRef` and move external API surface there.
5. Remove public orchestrator `start()`.
6. Unify orchestrator state around `runtimeByPipeline`.
7. Move watchdog startup and cleanup into actor hooks.
8. Update DSL helper to work with the orchestrator ref or orchestrator scope.
9. Refactor `PipelineRuntimeV2` onto the same actor model.
10. Update tests to construct actors only through `spawn(...)`.

---

## 17. Acceptance criteria

- No actor in `pipekt.runtime` requires a public "boot the loop" `start()` method.
- No actor stores a nullable loop job purely for one-time startup.
- No actor uses a startup `Mutex` solely to guarantee the loop starts once.
- Any actor-specific side job is started and stopped through lifecycle hooks rather than `init`.
- Mailbox behavior is bounded and explicit.
- Startup failure is visible to `spawn(...)`.
- Shutdown is idempotent and single-flight.
- Actor shutdown is not blocked behind arbitrary mailbox backlog as a normal queued command.
- Public shutdown waits for actor termination before returning.
- Public callers interact only through typed refs.
- Public callers never use generic ask or tell.

---

## 18. Open decisions

- whether `ActorMailboxFullException` and `ActorMailboxClosedException` should remain internal or become public runtime exceptions
- whether mailbox capacity should default to `Channel.BUFFERED` everywhere or require explicit sizing per actor
- whether `PipelineRuntimeV2` should keep a domain `StartRuntime` command or collapse startup fully into `spawn(...)`

Current recommendation:

- keep actor exceptions internal first
- keep timeout optional on `shutdown(...)`
- migrate `PipelineRuntimeV2` in a second step after orchestrator refactor lands cleanly

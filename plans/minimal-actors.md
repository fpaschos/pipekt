# Minimal actors for PipeKt

**Purpose:** Define the canonical actor model used in PipeKt. This document is synchronized to the current implementation in `io.github.fpaschos.pipekt.actor` and describes how runtime components should consume it. `PipelineOrchestrator` is used as a worked example, not as the only target actor.

**Status:** Active design and implementation reference.

**Related:** [pipeline-implementation-v2.md](pipeline-implementation-v2.md), [streams-technical-requirements.md](streams-technical-requirements.md), [streams-core-architecture.md](streams-core-architecture.md).

---

## 1. Scope

This document standardizes the minimal actor infrastructure used in PipeKt.

It applies to:

- shared actor primitives in `io.github.fpaschos.pipekt.actor`
- runtime coordinators such as `PipelineOrchestrator`
- future actor-like runtime components

It does not introduce a general public actor framework. The shared actor package is intentionally small and capability-oriented.

---

## 2. Goals

- Remove repeated actor bootstrapping code from runtime classes.
- Remove public `start()` methods whose only purpose is to launch a mailbox loop.
- Separate actor infrastructure from domain commands.
- Expose actors externally only through generic typed refs.
- Support universal `tell` and `ask` without per-actor ref boilerplate.
- Let actors and non-actor callers interact through the same ref abstraction.
- Provide a universal generic `spawn(...)` entry point.
- Support actor-owned startup and shutdown hooks.
- Make startup, shutdown, failure handling, and mailbox semantics explicit.
- Keep actor transport ergonomics compact and Kotlin-idiomatic.
- Keep the hot path mailbox-serialized and lightweight.

---

## 3. Non-goals

- No supervision tree.
- No automatic actor restart.
- No priority mailbox.
- No actor system object.
- No actor context object.
- No public generic actor DSL.
- No Akka-style behavior model.

---

## 4. Core design

The actor model has three layers:

1. `Actor<Command>`
   - Shared infrastructure.
   - Owns mailbox, loop job, startup barrier, termination barrier, lifecycle state, and shutdown behavior.

2. Concrete actor implementation
   - Defines a sealed command protocol `Command`.
   - Implements `handle(command)`.
   - Optionally overrides `postStart()`, `preStop()`, and `postStop()`.

3. `ActorRef<Command>`
   - Generic typed handle returned by `spawn(...)`.
   - Exposes universal `tell(command)` and `ask(...)`.
   - Is the only supported way actors or outsiders communicate with an actor.
   - Does not expose mailbox or internal mutable state.

Construction rules:

- actor constructors are `private` or `internal`
- `spawn(...)` is the construction entry point
- `spawn(...)` waits for startup to succeed
- the loop is not started from `init`

Design constraints:

- there is no `ActorSystem`
- there is no `ActorContext`
- actors are modeled as classes, not returned behaviors
- child actor creation, if needed later, should build on the same generic `spawn(...)` primitive rather than a required context object

### 4.1 Naming model

Actor naming is intentionally lightweight.

The model distinguishes:

- `name`: caller-provided semantic name such as `pipeline-orchestrator`
- `label`: process-local unique diagnostic label such as `pipeline-orchestrator#7`

Rules:

- `name` is not globally unique
- multiple actor instances may share the same semantic name
- `label` is generated internally from a process-local counter
- `label` is used for coroutine names, error messages, and diagnostics

This avoids a global actor registry while still keeping diagnostics unambiguous.

---

## 5. Lifecycle model

### 5.1 Infrastructure lifecycle

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
- `SHUTTING_DOWN`: no new commands accepted; termination is in progress
- `SHUTDOWN`: loop terminated and terminal cleanup completed

### 5.2 Domain lifecycle vs actor lifecycle

Actor lifecycle is infrastructure-level only.

Examples:

- `PipelineRuntimeV2` may still keep its own domain lifecycle
- a future coordinator actor may keep `IDLE/ACTIVE/FAILED`
- `ActorLifecycle` only answers whether the mailbox host is alive and accepting commands

Do not collapse domain state into actor infrastructure state.

---

## 6. Shared actor package

The current implementation lives under:

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/Actor.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/ActorRef.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/RequestReply.kt`

### 6.1 `Actor.kt`

Reference implementation:

```kotlin
/**
 * Lifecycle states for the shared actor infrastructure.
 *
 * - [STARTING]: Actor exists but [Actor.postStart] has not completed successfully yet.
 * - [RUNNING]: Accepts new commands.
 * - [SHUTTING_DOWN]: No new commands accepted; draining or being cancelled.
 * - [SHUTDOWN]: Loop terminated and cleanup completed.
 */
internal enum class ActorLifecycle {
    STARTING,
    RUNNING,
    SHUTTING_DOWN,
    SHUTDOWN,
}

/**
 * Base failure type for actor transport and execution failures surfaced through [Result].
 */
sealed class ActorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Actor could not accept a command or could not complete a previously accepted command
 * because it became unavailable.
 */
enum class ActorUnavailableReason {
    /** The actor rejected the command before acceptance because it is no longer running. */
    ACTOR_CLOSED,

    /** The actor is running, but the mailbox is at capacity and could not accept the command. */
    MAILBOX_FULL,

    /**
     * The command had been accepted into the mailbox but was never executed by [Actor.handle]
     * because shutdown failed pending reply-bearing commands before delivery.
     */
    NOT_DELIVERED,
}

class ActorUnavailable(
    val reason: ActorUnavailableReason,
    actorLabel: String,
    cause: Throwable? = null,
) : ActorException("$actorLabel is unavailable", cause)

/**
 * Request/reply did not complete before the ask timeout elapsed.
 */
class ActorAskTimeout(
    actorLabel: String,
    timeout: Duration,
) : ActorException("$actorLabel did not reply within $timeout")

/**
 * Actor command handling failed after the command was accepted.
 */
class ActorCommandFailed(
    actorLabel: String,
    cause: Throwable,
) : ActorException("$actorLabel command failed", cause)

/**
 * Base actor infrastructure: mailbox, loop job, startup/termination barriers, lifecycle,
 * and shutdown behavior. Concrete actors define [Command], implement [handle], and
 * optionally override [postStart], [preStop], [postStop], and observability hooks for
 * undelivered commands.
 *
 * Construction is via a suspend `spawn(...)` that waits for [awaitStarted] and returns
 * a ref; the loop is not started from `init` and is not a separate public lifecycle.
 *
 * @param Command Sealed command type for this actor.
 * @param scope Scope that owns the mailbox loop; cancellation of this scope terminates the actor.
 * @param name Name used for the loop coroutine and error messages.
 * @param capacity Mailbox channel capacity; default is [Channel.BUFFERED].
 */
abstract class Actor<Command : Any>(
    private val scope: CoroutineScope,
    private val name: String,
    capacity: Int = Channel.BUFFERED,
) {
    private val actorInstanceId = nextActorInstanceId.incrementAndGet()
    private val label = "$name#$actorInstanceId"

    /** Bounded mailbox for commands. */
    protected val mailbox = Channel<Command>(capacity)

    private val lifecycleMutex = Mutex()
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()

    private val lifecycle = atomic(ActorLifecycle.STARTING)

    private val loopJob: Job =
        scope.launch(CoroutineName(label)) {
            try {
                // Run actor-owned startup before the actor becomes externally usable.
                // If this throws, spawn()/awaitStarted() fail and no ref is returned.
                postStart()

                // Only a coroutine that still sees STARTING may publish RUNNING.
                // Shutdown may have won the race while postStart() was running.
                val startedNow =
                    lifecycleMutex.withLock {
                        if (lifecycle.value != ActorLifecycle.STARTING) {
                            false
                        } else {
                            lifecycle.value = ActorLifecycle.RUNNING
                            true
                        }
                    }

                if (!startedNow) {
                    // Exit without entering the mailbox drain loop; fail startup so spawn() does not hang.
                    if (!started.isCompleted) {
                        started.completeExceptionally(
                            CancellationException("$label was stopped during startup"),
                        )
                    }
                    return@launch
                }

                // Publish the actor as started. From this point, refs may use it.
                started.complete(Unit)

                // Drain mailbox commands one at a time.
                for (command in mailbox) {
                    try {
                        handle(command)
                    } catch (t: Throwable) {
                        // Command failure is actor-fatal by default.
                        onCommandFailure(command, t)
                        mailbox.close(t)
                        throw t
                    }
                }
            } catch (t: Throwable) {
                // Startup or loop infrastructure failed; fail the startup barrier so spawn()/awaitStarted() do not hang.
                if (!started.isCompleted) {
                    started.completeExceptionally(t)
                }
                throw t
            } finally {
                // Publish terminal lifecycle before releasing shutdown waiters.
                lifecycle.value = ActorLifecycle.SHUTDOWN
                try {
                    postStop()
                } finally {
                    // Shutdown callers and tests can now observe completion.
                    terminated.complete(Unit)
                }
            }
        }

    /**
     * Suspends until the actor has transitioned to [ActorLifecycle.RUNNING].
     * Fails if startup failed or the actor was stopped during startup.
     */
    suspend fun awaitStarted() {
        started.await()
    }

    /**
     * Suspends until the actor loop has terminated and [ActorLifecycle.SHUTDOWN] is set.
     */
    suspend fun awaitTerminated() {
        terminated.await()
    }

    /** Process one command. Called from the mailbox loop; one failure does not kill the actor. */
    protected abstract suspend fun handle(command: Command)

    /**
     * Hook run before the mailbox drain loop starts. Use for actor-specific side jobs
     * (watchdogs, pollers, child cleanup). Failures here cause startup to fail.
     */
    protected open suspend fun postStart() {}

    /**
     * Hook run when shutdown begins, after the mailbox is closed. Use to stop side jobs
     * and release actor-owned resources.
     */
    protected open suspend fun preStop() {}

    /**
     * Hook run after the actor loop has terminated. Use this when cleanup must happen
     * only after command draining/cancellation has completed.
     */
    protected open suspend fun postStop() {}

    /**
     * Called when [handle] throws.
     *
     * Default behavior:
     * - complete the failing reply-bearing command exceptionally with [ActorCommandFailed]
     * - stop the actor by rethrowing from the loop
     */
    protected open suspend fun onCommandFailure(
        command: Command,
        cause: Throwable,
    ) {
        if (command is ReplyRequest<*>) {
            command.failRequest(ActorCommandFailed(label, cause))
        }
    }

    /**
     * Called for commands accepted earlier but never delivered to [handle].
     *
     * Default behavior is:
     * - if the command carries shared reply transport, fail it with
     *   [ActorUnavailable]
     * - otherwise do nothing
     *
     * Concrete actors may override this for logging or metrics.
     */
    protected open fun onUndeliveredCommand(
        command: Command,
        reason: ActorUnavailableReason,
    ) {
        if (command is ReplyRequest<*>) {
            command.failRequest(
                ActorUnavailable(
                    reason = reason,
                    actorLabel = label,
                ),
            )
        }
    }

    /** Throws if [lifecycle] is not [ActorLifecycle.RUNNING]. */
    protected fun ensureAccepting() {
        check(lifecycle.value == ActorLifecycle.RUNNING) {
            "$name is not accepting new commands: ${lifecycle.value}"
        }
    }

    /**
     * Non-blocking send.
     *
     * Returns [Result.success] when [command] is accepted into the mailbox.
     * Returns [Result.failure] with [ActorUnavailable] when the actor is not
     * accepting commands or when the mailbox cannot accept the command.
     */
    protected fun send(command: Command): Result<Unit>

    /**
     * Shuts down the actor.
     *
     * Shutdown is single-flight: only the first caller performs the state transition and
     * shutdown work; later callers simply wait for [awaitTerminated].
     *
     * Shutdown order:
     * 1. Move the actor from [ActorLifecycle.STARTING] or [ActorLifecycle.RUNNING] to
     *    [ActorLifecycle.SHUTTING_DOWN].
     * 2. Close the mailbox so no new commands are accepted.
     * 3. If [gracefully] is `true`, run [preStop] and allow the actor to terminate normally.
     * 4. If graceful shutdown exceeds [timeout], or if [gracefully] is `false`, cancel the
     *    actor loop and wait for termination.
     *
     * Notes:
     * - [timeout] only has meaning when [gracefully] is `true`.
     * - [preStop] is included in the graceful timeout budget, so it must be cancellation-cooperative.
     * - When this function returns, the actor loop has terminated.
     */
    protected suspend fun shutdown(
        gracefully: Boolean = true,
        timeout: Duration? = null,
    ) {
        require(gracefully || timeout == null) {
            "timeout is only valid when gracefully = true"
        }

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

        if (!shouldStop) {
            terminated.await()
            return
        }

        // Stop accepting new work immediately. Buffered commands may still drain unless we
        // later escalate to loop cancellation.
        mailbox.close()

        suspend fun forceShutdown() {
            // Hard stop: cancel the actor loop and wait until final termination is observed.
            loopJob.cancel()
            terminated.await()
        }

        if (!gracefully) {
            // Immediate shutdown skips graceful waiting entirely.
            forceShutdown()
            return
        }

        if (timeout == null) {
            // Unbounded graceful shutdown:
            // 1. stop actor-owned side jobs/resources
            // 2. allow normal loop termination
            // 3. wait until termination is complete
            preStop()
            terminated.await()
            return
        }

        val completedGracefully =
            withTimeoutOrNull(timeout) {
                // preStop is part of the graceful shutdown budget.
                preStop()
                terminated.await()
                true
            } == true

        if (!completedGracefully) {
            // Graceful shutdown exceeded the timeout. Escalate to hard cancellation.
            forceShutdown()
        }
    }
}
```

### 6.2 `RequestReply.kt`

Reference implementation:

```kotlin
/**
 * Small request/reply transport used by actor commands.
 *
 * Command protocols see only this abstraction; the deferred used by [ask] stays internal to the
 * actor package.
 */
interface ReplyChannel<in Reply> {
    fun success(value: Reply): Boolean

    fun failure(cause: Throwable): Boolean
}

/**
 * Marker for commands that carry a shared reply transport.
 */
interface ReplyRequest<Reply> {
    val replyTo: ReplyChannel<Reply>
}

/**
 * Shared base class for request commands so each message does not need to reimplement failure
 * plumbing.
 */
abstract class Request<Reply>(
    final override val replyTo: ReplyChannel<Reply>,
) : ReplyRequest<Reply> {
    fun success(value: Reply): Boolean = replyTo.success(value)

    fun failure(cause: Throwable): Boolean = replyTo.failure(cause)
}
```

### 6.2.1 Reply ergonomics follow-up

The current shared request shape removes the old per-command failure boilerplate.

Recommended direction:

- keep the shared request marker concept
- keep failure wiring in the shared request base class
- hide `CompletableDeferred` from command protocols where practical

Preferred shapes:

1. Introduce a small shared reply transport abstraction, for example:

```kotlin
interface ReplyChannel<in T> {
    fun success(value: T): Boolean
    fun failure(cause: Throwable): Boolean
}
```

2. Let `ask(...)` create the concrete deferred-backed implementation internally.

3. Let reply-bearing commands carry a `ReplyChannel<T>` or similar responder rather than a raw
   `CompletableDeferred<T>`.

4. Use shared request-carrying infrastructure implemented once in the actor package.

Example target shape:

```kotlin
interface ReplyRequest<Reply> {
    val replyTo: ReplyChannel<Reply>
}

abstract class Request<Reply>(
    final override val replyTo: ReplyChannel<Reply>,
) : ReplyRequest<Reply> {
    fun success(value: Reply): Boolean = replyTo.success(value)

    fun failure(cause: Throwable): Boolean = replyTo.failure(cause)
}

data class Ping(
    val value: String,
    private val channel: ReplyChannel<String>,
) : Request<String>(channel)
```

The exact API may differ, but the design goal is stable:

- command protocols should describe reply intent, not coroutine deferred mechanics
- per-command failure plumbing should be eliminated

### 6.2.2 Why not expose raw `CompletableDeferred`

The current `ask(...)` helper exposes `CompletableDeferred` directly in the command builder.

That is acceptable for a minimal first implementation, but it is not the desired end state.

Problems with exposing `CompletableDeferred` in command protocols:

- it leaks coroutine transport details into domain command types
- it makes request/reply commands more verbose than necessary
- it encourages transport-aware command APIs rather than actor-aware ones
- it makes it harder to evolve later toward reply refs or one-shot responders

Target direction:

- external callers should still use `ask(timeout) { ... }`
- command builders should receive a small reply abstraction rather than a raw deferred
- actor handlers should reply via `replyTo.success(...)` or equivalent
- request failure wiring should live in shared request infrastructure, not in every command type

This is closer to the actor intent found in systems such as Akka Typed, where a command carries
`replyTo` rather than a future/promise object.

### 6.3 `ActorRef.kt`

Reference implementation:

```kotlin
/**
 * Generic typed handle to an actor.
 *
 * All communication with actors, from inside or outside the actor layer, goes through
 * [tell] or [ask]. Concrete per-actor ref subclasses are not required by the core model.
 */
interface ActorRef<in Command : Any> {
    val name: String
    val label: String

    /**
     * Sends a command without waiting for a reply.
     *
     * Result is [Result.success] when the command was accepted into the mailbox.
     * Result is [Result.failure] with [ActorUnavailable] when the actor cannot
     * accept the command.
     */
    fun tell(command: Command): Result<Unit>

    /**
     * Shuts down the actor. When this returns, the actor loop has terminated and no more
     * commands are processed.
     *
     * @param timeout If non-null, graceful shutdown is attempted; if the timeout expires,
     *   the implementation may force-cancel and then await termination.
     */
    suspend fun shutdown(timeout: Duration? = null)
}

/**
 * Universal request/reply helper built on reply-bearing commands.
 *
 * The actor library does not model response types in [ActorRef] directly. Instead, the
 * command protocol carries the reply transport, and [ask] creates and awaits it.
 */
suspend fun <Command : Any, Reply> ActorRef<Command>.ask(
    timeout: Duration,
    block: (ReplyChannel<Reply>) -> Command,
): Result<Reply> {
    val reply = deferredReplyChannel<Reply>()
    val enqueue = tell(block(reply))
    if (enqueue.isFailure) {
        return Result.failure(enqueue.exceptionOrNull()!!)
    }
    return TODO("reference shape only")
}
```

---

## 7. Concurrency model

### 7.1 Why atomic + mutex are both used

The actor uses:

- atomic lifecycle state for cheap cross-coroutine visibility on the fast path
- `Mutex` only for coordinated lifecycle transitions

This split is intentional:

- `atomic` handles read-mostly checks like whether the actor is still accepting commands
- `Mutex` makes startup and shutdown state transitions single-flight and race-safe

The mutex is not used for:

- command handling
- actor business state mutation
- long-running cleanup

### 7.2 Startup sequence

The loop startup sequence is:

1. run `postStart()`
2. under `lifecycleMutex`, verify lifecycle is still `STARTING`
3. if yes, publish `RUNNING`
4. complete `started`
5. enter mailbox drain loop

If shutdown wins during `postStart()`, startup fails and the actor exits without entering the drain loop.

### 7.3 Shutdown sequence

The shutdown sequence is:

1. under `lifecycleMutex`, move to `SHUTTING_DOWN`
2. close mailbox
3. if graceful, run `preStop()` and wait for termination
4. if timeout expires, fail pending queued commands as undelivered and cancel the loop
5. in the loop `finally`, publish `SHUTDOWN`
6. run `postStop()`
7. complete `terminated`

### 7.4 Why `preStop()` and `postStop()` both exist

`preStop()`:

- runs during shutdown initiation
- is used to stop side jobs and owned resources
- is part of the graceful shutdown budget

`postStop()`:

- runs after the loop has terminated
- is used only for cleanup that must happen after draining or cancellation

If an actor does not need true post-termination cleanup, it can ignore `postStop()`.

---

## 8. Failure handling

### 8.1 Command failure

Default rule:

- a throwable escaping [handle] is actor-fatal by default

Implementation:

- each mailbox dispatch is wrapped in `try/catch`
- if [handle] throws, the base actor completes the failing reply-bearing command exceptionally
  with `ActorCommandFailed`
- after that, the actor stops instead of continuing with later commands
- command failures surfaced through `ask(...)` should be wrapped as `ActorCommandFailed`

This is intentional.

Reasoning:

- once actor-owned state or resources have observed an unexpected exception, continuing to
  process later commands is harder to reason about
- PipeKt does not have supervision/restart semantics in the minimal actor layer
- stopping the actor is the safer default

Concrete actors may still override `onCommandFailure(...)` for domain-specific behavior before termination.

### 8.2 Startup failure

If `postStart()` fails:

- actor startup fails
- `awaitStarted()` fails
- `spawn(...)` fails
- no ref should escape representing a half-started actor

### 8.3 Restart strategy

Automatic restart is out of scope.

Rule:

- actors are one-shot
- once terminated, create a new actor via `spawn(...)`

### 8.4 Pending messages when the actor stops

When an actor stops because of shutdown or internal failure, pending commands still in the
mailbox need an explicit policy.

This design uses the following rules:

- pending reply-bearing commands are failed exceptionally with `ActorUnavailable`
- the reason should be `ActorUnavailableReason.NOT_DELIVERED`
- pending one-way commands are dropped
- there is no dead-letter subsystem in the minimal actor library

This means:

- `tell(...)` may have returned `Result.success(Unit)` for a command that is later dropped
- `ask(...)` must not be left suspended forever once the command has been accepted

### 8.5 Observability of undelivered commands

The core library should not require a dead-letter bus or logging framework.

Instead, the base actor should expose a minimal observability hook:

```kotlin
protected open fun onUndeliveredCommand(
    command: Command,
    reason: ActorUnavailableReason,
) {}
```

Default behavior:

- if `command` carries a shared request reply channel, fail it with
  `ActorUnavailable(reason = NOT_DELIVERED, ...)`
- otherwise do nothing

Concrete actors may override this hook to:

- log dropped one-way commands
- record metrics
- attach actor-specific diagnostics

---

## 9. Mailbox behavior

### 9.1 Bounded mailbox

The mailbox is finite and fail-fast by default.

Default behavior:

- non-blocking channel send is used internally
- if the mailbox is full, `tell(...)` / `ask(...)` return `Result.failure(ActorUnavailable)`
- if the actor is not accepting commands, `tell(...)` / `ask(...)` return `Result.failure(ActorUnavailable)`
- `tell(...)` does not suspend for mailbox space
- mailbox capacity is a protection boundary, not a backpressure API

### 9.2 Rejection semantics

Public transport failures are intentionally compressed:

- `ActorUnavailable`
- `ActorAskTimeout`
- `ActorCommandFailed`

This is a deliberate ergonomics tradeoff.

Callers usually care about only:

- the actor could not accept or complete the command because it was unavailable
- the actor did not reply before the timeout
- the actor handled the command and failed

When callers need more detail about unavailability, they inspect:

- `ActorUnavailable.reason == ACTOR_CLOSED`
- `ActorUnavailable.reason == MAILBOX_FULL`
- `ActorUnavailable.reason == NOT_DELIVERED`

The core library should avoid exposing more transport-specific exception types unless a
real use case justifies them.

### 9.3 Public tell/ask model

The public transport contract is intentionally small and universal:

- `ActorRef<Command>.tell(command): Result<Unit>`
- `ActorRef<Command>.ask(timeout) { replyTo -> Command(replyTo, ...) }: Result<Reply>`

Rules:

- `tell(...)` is the universal one-way send
- `tell(...)` is non-blocking and returns immediately
- `ask(...)` is a helper layered on top of reply-bearing commands
- `ask(...)` always requires a timeout
- response typing belongs to the command protocol, not to `ActorRef` itself
- there is no separate untyped public transport API in the core library
- the public API uses Kotlin `Result` rather than exposing mailbox transport exceptions directly

Ergonomic follow-up:

- `ask(...)` may continue to use `CompletableDeferred` internally
- the command builder API should use a small reply abstraction such as `ReplyChannel<T>`
- command types should not implement reply failure plumbing one by one
- shared request infrastructure should bridge actor failures into the reply transport

---

## 10. External access model

External callers interact only through typed refs.

Canonical shape:

```kotlin
interface ActorRef<in Command : Any> {
    val name: String
    val label: String
    fun tell(command: Command): Result<Unit>
    suspend fun shutdown(timeout: Duration? = null)
}
```

They do not:

- access an actor instance directly
- inspect mailbox state

This is intentional. Public callers address actors only through a typed protocol.

The same rule applies to actor-to-actor communication:

- one actor talks to another only through `ActorRef<Command>`
- actors do not call each other's internal methods directly

Diagnostic identity rules:

- `name` is semantic and may collide across instances
- `label` is process-local unique and should be used in logs and errors

---

## 11. Spawn pattern

The library should expose a universal generic `spawn(...)` function.

```kotlin
suspend fun <Command : Any> spawn(
    name: String,
    dispatcher: CoroutineDispatcher? = null,
    factory: (CoroutineScope, String) -> Actor<Command>,
): ActorRef<Command>
```

Reference behavior:

- `spawn(...)` is `suspend`
- `spawn(...)` captures the current coroutine context as the parent context
- actor scope construction stays internal; callers do not pass a `CoroutineScope`
- actor scope creation always uses a supervised child job
- the inherited dispatcher is preserved unless an explicit dispatcher override is provided
- `spawn(...)` must not return before startup succeeds
- callers never invoke a separate public loop-boot `start()`
- outsiders start actors through `spawn(...)`, not via public constructors
- actor constructors should remain `private` or `internal`
- concrete actors may still offer companion helpers, but those helpers should delegate to the generic `spawn(...)`

Reference implementation shape:

```kotlin
suspend fun <Command : Any> spawn(
    name: String,
    dispatcher: CoroutineDispatcher? = null,
    factory: (CoroutineScope, String) -> Actor<Command>,
): ActorRef<Command> {
    val parentScope = CoroutineScope(currentCoroutineContext())
    val scope = createActorScope(parentScope, name, dispatcher)
    val actor = factory(scope, name)
    actor.awaitStarted()
    return actor.self()
}
```

This is now the canonical public spawn API.

### 11.1 Scope inheritance and convenience model

The convenience model is intentionally opinionated.

Rules:

- callers should not assemble actor scopes manually for normal actor creation
- the ambient coroutine becomes the ownership boundary for the actor
- overriding only the dispatcher is the public execution-policy knob
- supervision policy is internal and fixed to supervised child scopes
- if code needs direct access to the concrete actor instance rather than an `ActorRef`, it may still construct the actor directly in tests or internal infrastructure

Rationale:

- this keeps top-level actor construction Kotlin-idiomatic in suspend code
- it hides `SupervisorJob(...)` and scope assembly from callers
- it preserves deterministic `runTest` scheduling because the current coroutine context is inherited automatically
- it keeps ownership explicit enough for structured concurrency without introducing an actor system object

### 11.2 Internal scope model

Actors that own side jobs or child actors should distinguish between two internal scopes:

- `actorScope`: owns the mailbox loop
- `childScope`: owns actor-managed side jobs and child actors

Rules:

- `actorScope` termination ends the actor loop
- `childScope` is derived from the actor's context and is cancelled during actor shutdown
- actor-owned watchdogs, pollers, and child actors should use `childScope`
- child cleanup must not cancel the actor loop out from under itself
- actor-owned scopes created internally by `spawn(...)` must be cancelled when the actor terminates, including crash paths, so test and parent coroutine trees do not leak jobs

### 11.3 Observations from implementation

The current implementation surfaced a few practical rules that should remain documented:

- normal actor shutdown and actor crash paths both need to cancel the internally owned actor scope job
- child watch notifications should treat post-stop cancellation as normal termination when the child has already reached `SHUTDOWN`
- tests that only need an `ActorRef` should use `spawn(name) { scope, name -> ... }`
- tests that need `awaitStarted()`, `awaitTerminated()`, or other concrete actor internals may still instantiate the actor directly

Example convenience usage:

```kotlin
val ref =
    spawn("pipeline-orchestrator") { scope, name ->
        PipelineOrchestratorActor(scope, name, deps)
    }
```

Example direct construction for infrastructure-only tests:

```kotlin
val actor = MinimalActor(scope, "shutdown-during-startup", startupGate = startupGate)
val shutdown = async { actor.self().shutdown() }
val startupFailure = async { runCatching { actor.awaitStarted() } }
```

This model is especially important for orchestrator-style actors.

### 11.3 Why there is no actor system

This design does not require an `ActorSystem`.

Reasoning:

- PipeKt does not need a registry, supervision tree, or framework-wide runtime object
- the current `Actor` base already owns the mailbox loop and startup/termination barriers
- a top-level generic `spawn(...)` is sufficient to construct actors and return typed refs

If a future need appears for shared actor runtime services, that can be introduced later.
It is not part of the minimal actor model.

### 11.4 Why there is no actor context

This design does not require a mandatory `ActorContext`.

Reasoning:

- actors are implemented as classes with local state and lifecycle hooks
- `handle(command)` is sufficient for the intended use cases
- PipeKt does not currently need Akka-style behavior switching or a required context object on every handler call

However, minimal actor-tree support is now required.

The next iteration should support:

- spawn helpers that inherit scope from a parent actor
- owned child tracking
- termination watching

Those capabilities should be added as focused actor primitives without forcing a general-purpose
`ActorContext` parameter into every actor API.

The same applies to request/reply transport:

- actors may use small shared request carrier types
- actors should not be forced to implement transport plumbing on every request command
- domain command types should stay close to domain intent

---

## 12. Worked example: `PipelineOrchestrator`

`PipelineOrchestrator` has not yet been migrated to this actor package, but when it is, it should consume the shared actor primitives above.

### 12.1 Responsibilities

`PipelineOrchestrator` should:

- own the map of active runtimes by pipeline name
- serialize pipeline start/stop/list operations
- own one store-level watchdog loop
- create runtime refs for started pipelines
- shut down all active runtimes when the orchestrator stops

### 12.2 Scope model

The orchestrator should use two scopes:

- `actorScope`: runs the actor loop
- `childScope`: owns watchdog and child runtimes

This prevents actor-owned child cleanup from cancelling the actor loop out from under itself.

More generally, orchestrator-owned runtimes should be treated as owned children:

- the orchestrator tracks them explicitly
- the orchestrator shuts them down from `preStop()`
- the orchestrator may watch their termination and react by self-sending domain messages

### 12.2.1 Child watch model

The minimal actor package should gain a lightweight watch mechanism for owned child actors.

Goal:

- when an owned child stops, whether normally or due to failure, the parent can observe that fact
- the parent decides whether to ignore it, recreate the child, or escalate

Recommended semantics:

- parent stop causes owned children to stop
- child failure does not automatically crash the parent
- child termination is surfaced to the parent as a normal self-message
- restart policy remains domain-specific and lives in the parent actor, not in the shared actor runtime

Example event shape:

```kotlin
data class ChildTerminated(
    val childLabel: String,
    val cause: Throwable?,
)
```

This is sufficient for cases such as:

- orchestrator restarts a failed pipeline child
- orchestrator notices watchdog termination and recreates it
- parent actor maintains desired child topology without a full supervision framework

### 12.3 Example protocol shape

```kotlin
sealed interface PipelineOrchestratorCommand {
    data class StartPipeline(
        val definition: PipelineDefinition,
        val planVersion: String,
        val config: RuntimeConfig,
        val replyTo: ReplyChannel<RuntimeRef>,
    ) : PipelineOrchestratorCommand, ReplyRequest<RuntimeRef>

    data class StopPipeline(
        val pipelineName: String,
    ) : PipelineOrchestratorCommand

    data class ListPipelines(
        val replyTo: ReplyChannel<Set<String>>,
    ) : PipelineOrchestratorCommand, ReplyRequest<Set<String>>

    data class RuntimeTerminated(
        val pipelineName: String,
        val cause: Throwable?,
    ) : PipelineOrchestratorCommand
}
```

In concrete code, commands like `StartPipeline` and `ListPipelines` should normally extend or use
shared request-carrying infrastructure so they do not each implement reply failure plumbing.

In this model the public handle is simply:

```kotlin
ActorRef<PipelineOrchestratorCommand>
```

External callers then use:

```kotlin
val orchestrator: ActorRef<PipelineOrchestratorCommand> = spawn { /*...*/ }

orchestrator.tell(PipelineOrchestratorCommand.StopPipeline("orders"))

val pipelines =
    orchestrator.ask(5.seconds) { replyTo ->
        PipelineOrchestratorCommand.ListPipelines(replyTo)
    }.getOrThrow()
```

In the target design, the orchestrator may also watch owned runtime children and react through
self-sent `RuntimeTerminated(...)` commands rather than embedding restart logic outside the actor.

---

## 13. Tests

The shared actor package currently has focused tests under:

- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorTest.kt`
- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/MinimalActor.kt`

Current coverage includes:

- spawn waits for startup
- repeated requests before shutdown
- shutdown rejects later requests
- idempotent concurrent shutdown
- actor stops on command failure
- pending reply-bearing commands fail as not-delivered when termination wins
- shutdown during startup

---

## 14. File-level implementation status

Implemented:

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/Actor.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/ActorRef.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/RequestReply.kt`

Consumers still to migrate:

- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/PipelineOrchestrator.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/PipelineRuntimeV2.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/Types.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/runtime/PipelineDsl.kt`

---

## 15. Acceptance criteria

- No actor requires a public “boot the loop” `start()` method.
- Startup is acknowledged via `awaitStarted()`.
- Shutdown is single-flight and explicit.
- Mailbox behavior is bounded and explicit.
- Public callers interact only through `ActorRef<Command>`.
- `tell(...)` and `ask(...)` are universal across actors.
- `tell(...)` returns `Result<Unit>`.
- `ask(...)` returns `Result<Reply>` and always requires a timeout.
- Public failures are compressed to unavailable / timeout / command-failed.
- `ActorUnavailable` carries a reason enum for closed / full / not-delivered.
- Actor names are semantic labels and may collide.
- Each actor also has a process-local unique diagnostic label of the form `name#instanceId`.
- An exception escaping `handle(...)` stops the actor.
- Pending reply-bearing commands are failed on termination; pending one-way commands are dropped.
- The base actor exposes an undelivered-command hook instead of a dead-letter subsystem.
- No per-concrete actor ref type is required by the core model.
- No actor system or actor context is required by the core model.
- Reply-bearing command failures do not leave requesters suspended forever; shared request infrastructure bridges actor failure into the reply transport.

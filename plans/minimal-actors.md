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
- No public generic actor DSL.
- No Akka-style behavior model.

---

## 4. Core design

The actor model has three layers:

1. `ActorContext<Command>`
   - Small runtime capability surface provided to each actor.
   - Hides actor scope assembly and name wiring from concrete actor constructors.
   - Owns actor-local capabilities such as `self`, child `spawn(...)`, and owned-child `watch(...)`.

2. `Actor<Command>`
   - Shared infrastructure.
   - Owns mailbox, loop job, startup barrier, termination barrier, lifecycle state, and shutdown behavior.

3. Concrete actor implementation
   - Defines a sealed command protocol `Command`.
   - Implements `handle(command)`.
   - Optionally overrides `postStart()`, `preStop()`, and `postStop()`.

4. `ActorRef<Command>`
   - Generic typed handle returned by `spawn(...)`.
   - Exposes universal `tell(command)` and `ask(...)`.
   - Is the only supported way actors or outsiders communicate with an actor.
   - Does not expose mailbox or internal mutable state.

Construction rules:

- actor constructors are `private` or `internal`
- `spawn(...)` is the construction entry point
- `spawn(...)` waits for startup to succeed
- the loop is not started from `init`
- concrete actors receive an `ActorContext<Command>` rather than raw `CoroutineScope` and `name`

Design constraints:

- there is no `ActorSystem`
- actors are modeled as classes, not returned behaviors
- `ActorContext` is intentionally small and capability-oriented, not a general framework service locator
- child actor creation and owned-child watching are hosted on `ActorContext`

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
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/ActorContext.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/ActorRef.kt`
- `pipekt/src/commonMain/kotlin/io/github/fpaschos/pipekt/actor/RequestReply.kt`

Target shared package surface:

- `ActorContext<Command>` exposes runtime capabilities needed by concrete actors
- `Actor<Command>` owns mailbox and lifecycle infrastructure
- `ActorRef<Command>` is the only externally shared communication handle
- shared request/reply transport stays in the actor package

Reference context shape:

```kotlin
interface ActorContext<Command : Any> {
    val name: String
    val label: String
    val self: ActorRef<Command>

    suspend fun <ChildCommand : Any> spawn(
        name: String,
        dispatcher: CoroutineDispatcher? = null,
        factory: (ActorContext<ChildCommand>) -> Actor<ChildCommand>,
    ): ActorRef<ChildCommand>

    fun watch(
        child: ActorRef<*>,
        onTerminated: (ChildTermination) -> Command,
    )
}
```

Current semantics:

- `spawn(...)` creates an owned child actor
- owned children are shut down with the parent
- `watch(...)` applies only to actors previously created through the same actor's `spawn(...)`
- `watch(...)` is not a general-purpose watch for arbitrary actor refs

### 6.1 `Actor.kt`

Current implementation shape:

```kotlin
abstract class Actor<Command : Any>(
    protected val ctx: ActorContext<Command>,
    capacity: Int = Channel.BUFFERED,
) {
    // Resolves actor scope/name/label from the bound internal runtime context.
    // Owns the mailbox loop, lifecycle, startup/termination barriers, child scope,
    // and shutdown/failure handling.
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
3. if graceful, run `preStop()`, shut down owned children, and wait for termination
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
    factory: (ctx: ActorContext<Command>) -> Actor<Command>,
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
- `spawn(...)` creates the runtime context and passes it to the actor constructor
- concrete actors may still offer companion helpers, but those helpers should delegate to the generic `spawn(...)`

Reference implementation shape:

```kotlin
suspend fun <Command : Any> spawn(
    name: String,
    dispatcher: CoroutineDispatcher? = null,
    factory: (ctx: ActorContext<Command>) -> Actor<Command>,
): ActorRef<Command> {
    val parentScope = CoroutineScope(currentCoroutineContext())
    val scope = createActorScope(parentScope, name, dispatcher)
    val ctx = createActorContext(scope, name)
    val actor = factory(ctx)
    actor.awaitStarted()
    return ctx.self
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
- callers should not pass raw actor runtime parameters such as `CoroutineScope` and `name` into concrete actor constructors

Rationale:

- this keeps top-level actor construction Kotlin-idiomatic in suspend code
- it hides `SupervisorJob(...)` and scope assembly from callers
- it hides raw `scope` / `name` plumbing from actor construction
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
- tests that only need an `ActorRef` should use `spawn(name) { ctx -> ... }`
- tests that need `awaitStarted()`, `awaitTerminated()`, or other concrete actor internals may still instantiate the actor directly

Example convenience usage:

```kotlin
val ref =
    spawn("pipeline-orchestrator") { ctx ->
        PipelineOrchestratorActor(ctx, deps)
    }
```

Example direct construction for infrastructure-only tests:

```kotlin
val ctx = createActorContext<TestCommand>(scope, "shutdown-during-startup")
val actor = MinimalActor(ctx, startupGate = startupGate)
val shutdown = async { ctx.self.shutdown() }
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

### 11.4 Why there is a small actor context

This design now requires a small `ActorContext`, but not an Akka-style behavior model or a
general-purpose framework runtime object.

Reasoning:

- actors are implemented as classes with local state and lifecycle hooks
- `handle(command)` remains the dispatch model; no behavior switching is introduced
- the implementation now needs a coherent home for `self`, child `spawn(...)`, and `watch(...)`
- hiding raw `CoroutineScope` and actor `name` from concrete actor constructors improves ergonomics without changing the class-based model

`ActorContext` should stay intentionally small.

Required responsibilities:

- expose `self`
- expose `name` and `label`
- host child `spawn(...)`
- host owned-child `watch(...)`

Non-responsibilities:

- no behavior transitions
- no actor registry
- no service locator for arbitrary runtime dependencies
- no framework-wide mutable state

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
- observation is explicit: `spawn(...)` creates ownership, `watch(...)` registers for termination messages
- watching is limited to owned children rather than arbitrary actor refs

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

- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorLifecycleTest.kt`
- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorMailboxTest.kt`
- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorRequestReplyTest.kt`
- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorSpawnScopeTest.kt`
- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorChildOwnershipTest.kt`
- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorWatchTest.kt`
- `pipekt/src/commonTest/kotlin/io/github/fpaschos/pipekt/actor/ActorFixtures.kt`

Current coverage includes:

- spawn waits for startup
- startup failure does not publish a half-started ref
- shutdown during startup fails startup cleanly
- concurrent shutdown remains single-flight
- startup/shutdown hooks run in sequence
- tell/ask share the same generic typed ref surface
- actor command failure becomes `ActorCommandFailed`
- ask timeout becomes `ActorAskTimeout`
- one-way message order is preserved
- queued reply-bearing commands fail as not-delivered when termination wins
- undelivered one-way commands flow through `onUndeliveredCommand`
- owned children shut down with the parent
- owned-child watch events are emitted explicitly through `watch(...)`
- spawn preserves semantic `name` and unique diagnostic `label`
- `ctx.self` matches the published ref

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
- No actor system is required by the core model.
- A small `ActorContext` is required by the core model and remains limited to runtime actor capabilities.
- Reply-bearing command failures do not leave requesters suspended forever; shared request infrastructure bridges actor failure into the reply transport.

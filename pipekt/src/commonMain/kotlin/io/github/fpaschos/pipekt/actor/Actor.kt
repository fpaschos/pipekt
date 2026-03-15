package io.github.fpaschos.pipekt.actor

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

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

private val nextActorInstanceId = atomic(0L)

/**
 * Base actor infrastructure: mailbox, loop job, startup/termination barriers, lifecycle,
 * and shutdown behavior. Concrete actors define [Command], implement [handle], and
 * optionally override [postStart], [preStop], [postStop], [onCommandFailure], and
 * [onUndeliveredCommand].
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
    private val label = "${this@Actor.name}#$actorInstanceId"

    /** Bounded mailbox for commands. */
    protected val mailbox = Channel<Command>(capacity)

    private val lifecycleMutex = Mutex()
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()

    private val lifecycle = atomic(ActorLifecycle.STARTING)
    private val terminalCause = atomic<Throwable?>(null)
    private val children = mutableListOf<ActorChild<Command>>()
    private val childScope =
        CoroutineScope(
            scope.coroutineContext +
                SupervisorJob(scope.coroutineContext[Job]) +
                CoroutineName("${this@Actor.label}/children"),
        )

    /** Typed ref used by outsiders and peer actors to interact with this actor. */
    fun self(): ActorRef<Command> = actorRef

    private val actorRef =
        object : ActorRef<Command> {
            override val name: String
                get() = this@Actor.name

            override val label: String
                get() = this@Actor.label

            override fun tell(command: Command): Result<Unit> = send(command)

            override suspend fun shutdown(timeout: Duration?) {
                this@Actor.shutdown(timeout = timeout)
            }
        }

    private val loopJob: Job =
        scope.launch(CoroutineName(this@Actor.label)) {
            try {
                // Run actor owned startup before the actor becomes externally usable.
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
                            CancellationException("${this@Actor.label} was stopped during startup"),
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
                        terminalCause.value = t
                        onCommandFailure(command, t)
                        mailbox.close(t)
                        failPendingCommands()
                        throw t
                    }
                }
            } catch (t: Throwable) {
                // Startup or loop infrastructure failed; fail the startup barrier so spawn()/awaitStarted() do not hang.
                terminalCause.value = terminalCause.value ?: t
                if (!started.isCompleted) {
                    started.completeExceptionally(t)
                }
            } finally {
                // Publish the terminal lifecycle before releasing shutdown waiters.
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

    /** Process one command. Called from the mailbox loop. */
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
     * Default behavior completes the failing reply-bearing command exceptionally with
     * [ActorCommandFailed]. The actor stops after this hook returns.
     */
    protected open suspend fun onCommandFailure(
        command: Command,
        cause: Throwable,
    ) {
        if (command is ReplyRequest<*>) {
            command.failRequest(ActorCommandFailed(this@Actor.label, cause))
        }
    }

    /**
     * Called for commands accepted earlier but never delivered to [handle].
     *
     * Default behavior completes reply-bearing commands exceptionally with
     * [ActorUnavailable] and ignores one-way commands.
     */
    protected open fun onUndeliveredCommand(
        command: Command,
        reason: ActorUnavailableReason,
    ) {
        if (command is ReplyRequest<*>) {
            command.failRequest(
                ActorUnavailable(
                    reason = reason,
                    actorLabel = this@Actor.label,
                ),
            )
        }
    }

    /**
     * Non-blocking send.
     *
     * Returns [Result.success] when [command] is accepted into the mailbox.
     * Returns [Result.failure] with [ActorUnavailable] when the actor is not
     * accepting commands or when the mailbox cannot accept the command.
     */
    protected fun send(command: Command): Result<Unit> {
        if (lifecycle.value != ActorLifecycle.RUNNING) {
            return Result.failure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.ACTOR_CLOSED,
                    actorLabel = this@Actor.label,
                ),
            )
        }

        val result = mailbox.trySend(command)
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.MAILBOX_FULL,
                    actorLabel = this@Actor.label,
                    cause = result.exceptionOrNull(),
                ),
            )
        }
    }

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
                    -> {
                        false
                    }
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
            failPendingCommands()
            childScope.coroutineContext.job.cancel()
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
            // 2. stop owned children
            // 3. allow normal loop termination
            // 4. wait until termination is complete
            preStop()
            shutdownOwnedChildren()
            terminated.await()
            return
        }

        val completedGracefully =
            withTimeoutOrNull(timeout) {
                // preStop is part of the graceful shutdown budget.
                preStop()
                shutdownOwnedChildren()
                terminated.await()
                true
            } == true

        if (!completedGracefully) {
            // Graceful shutdown exceeded the timeout. Escalate to hard cancellation.
            forceShutdown()
        }
    }

    private fun failPendingCommands() {
        while (true) {
            val buffered = mailbox.tryReceive().getOrNull() ?: return
            onUndeliveredCommand(buffered, ActorUnavailableReason.NOT_DELIVERED)
        }
    }

    /**
     * Spawns an child actor under this actor's [childScope]. Owned children are stopped
     * during parent shutdown. If [onTerminated] is provided, child termination is converted into a
     * parent self-message.
     */
    protected suspend fun <ChildCommand : Any> spawnChild(
        name: String,
        dispatcher: CoroutineDispatcher? = null,
        supervisor: Boolean = true,
        onTerminated: ((ChildTermination) -> Command)? = null,
        factory: (CoroutineScope, String) -> Actor<ChildCommand>,
    ): ActorRef<ChildCommand> {
        val preparedScope = createActorScope(childScope, name, dispatcher, supervisor)
        val child = factory(preparedScope, name)
        child.awaitStarted()
        registerOwnedChild(child, onTerminated)
        return child.self()
    }

    /**
     * Observes a child previously created through [spawnChild]. Watch notifications are best
     * effort during shutdown: if the parent no longer accepts commands, the event is dropped.
     */
    protected fun watch(
        child: ActorRef<*>,
        onTerminated: (ChildTermination) -> Command,
    ) {
        val owned = children.firstOrNull { it.actor.self().label == child.label } ?: return
        owned.watchers += onTerminated
    }

    private fun registerOwnedChild(
        child: Actor<*>,
        watcher: ((ChildTermination) -> Command)?,
    ) {
        val owned =
            ActorChild(
                actor = child,
                watchers = mutableListOf<(ChildTermination) -> Command>(),
            )
        if (watcher != null) {
            owned.watchers += watcher
        }
        children += owned
        child.loopJob.invokeOnCompletion { cause ->
            val termination =
                ChildTermination(
                    childName = child.self().name,
                    childLabel = child.self().label,
                    cause = child.terminalCause.value ?: cause,
                )
            owned.watchers.forEach { watcherFn ->
                self().tell(watcherFn(termination))
            }
        }
    }

    private suspend fun shutdownOwnedChildren() {
        val children = children.toList()
        children.forEach { owned ->
            owned.actor.self().shutdown()
        }
        childScope.coroutineContext.job.cancel()
    }
}

data class ChildTermination(
    val childName: String,
    val childLabel: String,
    val cause: Throwable?,
)

private data class ActorChild<ParentCommand : Any>(
    val actor: Actor<*>,
    val watchers: MutableList<(ChildTermination) -> ParentCommand>,
)

private fun ReplyRequest<*>.failRequest(cause: Throwable) {
    @Suppress("UNCHECKED_CAST")
    (replyTo as ReplyChannel<Any?>).failure(cause)
}

private fun createActorScope(
    parentScope: CoroutineScope,
    actorName: String,
    dispatcher: CoroutineDispatcher?,
    supervisor: Boolean,
): CoroutineScope {
    val parentContext = parentScope.coroutineContext
    val childJob = if (supervisor) SupervisorJob(parentContext[Job]) else Job(parentContext[Job])
    var scopeContext = parentContext + childJob + CoroutineName(actorName)
    if (dispatcher != null) {
        scopeContext = scopeContext + dispatcher
    }
    return CoroutineScope(scopeContext)
}

/**
 * Starts an actor, waits for startup to complete, and returns its typed ref.
 */
suspend fun <Command : Any> spawn(factory: () -> Actor<Command>): ActorRef<Command> {
    val actor = factory()
    actor.awaitStarted()
    return actor.self()
}

/**
 * Starts an actor in a scope derived from [parentScope], waits for startup to complete, and
 * returns its typed ref.
 */
suspend fun <Command : Any> spawn(
    parentScope: CoroutineScope,
    name: String,
    dispatcher: CoroutineDispatcher? = null,
    supervisor: Boolean = true,
    factory: (CoroutineScope, String) -> Actor<Command>,
): ActorRef<Command> {
    val scope = createActorScope(parentScope, name, dispatcher, supervisor)
    val actor = factory(scope, name)
    actor.awaitStarted()
    return actor.self()
}

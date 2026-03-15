package io.github.fpaschos.pipekt.actor

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
 * @param actorName Name used for the loop coroutine and error messages.
 * @param capacity Mailbox channel capacity; default is [Channel.BUFFERED].
 */
abstract class Actor<Command : Any>(
    private val scope: CoroutineScope,
    private val actorName: String,
    capacity: Int = Channel.BUFFERED,
) {
    /** Bounded mailbox for commands. */
    protected val mailbox = Channel<Command>(capacity)

    private val lifecycleMutex = Mutex()
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()

    private val lifecycle = atomic(ActorLifecycle.STARTING)

    /** Typed ref used by outsiders and peer actors to interact with this actor. */
    fun self(): ActorRef<Command> = actorRef

    private val actorRef =
        object : ActorRef<Command> {
            override val actorName: String
                get() = this@Actor.actorName

            override fun tell(command: Command): Result<Unit> = send(command)

            override suspend fun shutdown(timeout: Duration?) {
                this@Actor.shutdown(timeout = timeout)
            }
        }

    private val loopJob: Job =
        scope.launch(CoroutineName(actorName)) {
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
                            CancellationException("$actorName was stopped during startup"),
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
                        failPendingCommands(ActorUnavailableReason.NOT_DELIVERED)
                        throw t
                    }
                }
            } catch (t: Throwable) {
                // Startup or loop infrastructure failed; fail the startup barrier so spawn()/awaitStarted() do not hang.
                if (!started.isCompleted) {
                    started.completeExceptionally(t)
                    throw t
                }
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
     * [ActorCommandFailedException]. The actor stops after this hook returns.
     */
    protected open suspend fun onCommandFailure(
        command: Command,
        cause: Throwable,
    ) {
        if (command is ReplyingCommand) {
            command.completeExceptionally(ActorCommandFailedException(actorName, cause))
        }
    }

    /**
     * Called for commands accepted earlier but never delivered to [handle].
     *
     * Default behavior completes reply-bearing commands exceptionally with
     * [ActorUnavailableException] and ignores one-way commands.
     */
    protected open fun onUndeliveredCommand(
        command: Command,
        reason: ActorUnavailableReason,
    ) {
        if (command is ReplyingCommand) {
            command.completeExceptionally(
                ActorUnavailableException(
                    reason = reason,
                    actorName = actorName,
                ),
            )
        }
    }

    /**
     * Non-blocking send.
     *
     * Returns [Result.success] when [command] is accepted into the mailbox.
     * Returns [Result.failure] with [ActorUnavailableException] when the actor is not
     * accepting commands or when the mailbox cannot accept the command.
     */
    protected fun send(command: Command): Result<Unit> {
        if (lifecycle.value != ActorLifecycle.RUNNING) {
            return Result.failure(
                ActorUnavailableException(
                    reason = ActorUnavailableReason.ACTOR_CLOSED,
                    actorName = actorName,
                ),
            )
        }

        val result = mailbox.trySend(command)
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                ActorUnavailableException(
                    reason = ActorUnavailableReason.MAILBOX_FULL,
                    actorName = actorName,
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
            failPendingCommands(ActorUnavailableReason.NOT_DELIVERED)
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

    private fun failPendingCommands(reason: ActorUnavailableReason) {
        while (true) {
            val buffered = mailbox.tryReceive().getOrNull() ?: return
            onUndeliveredCommand(buffered, reason)
        }
    }
}

/**
 * Starts an actor, waits for startup to complete, and returns its typed ref.
 */
suspend fun <Command : Any> spawn(factory: () -> Actor<Command>): ActorRef<Command> {
    val actor = factory()
    actor.awaitStarted()
    return actor.self()
}

package io.github.fpaschos.pipekt.actor

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
 * Thrown when a command is sent to an actor that is not accepting (e.g. already shut down).
 */
internal class ActorMailboxClosedException(
    actorName: String,
) : IllegalStateException("$actorName is not accepting new commands")

/**
 * Thrown when [Channel.trySend] fails because the mailbox is at capacity.
 */
internal class ActorMailboxFullException(
    actorName: String,
) : IllegalStateException("$actorName mailbox is full")

/**
 * Base actor infrastructure: mailbox, loop job, startup/termination barriers, lifecycle,
 * and shutdown behavior. Concrete actors define [Command], implement [handle], and
 * optionally override [postStart] and [preStop].
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

    private var lifecycle: ActorLifecycle = ActorLifecycle.STARTING

    private val loopJob: Job =
        scope.launch(CoroutineName(actorName)) {
            try {
                postStart()

                lifecycleMutex.withLock {
                    if (lifecycle != ActorLifecycle.STARTING) {
                        if (!started.isCompleted) {
                            started.completeExceptionally(
                                CancellationException("$actorName was stopped during startup"),
                            )
                        }
                        return@launch
                    }

                    lifecycle = ActorLifecycle.RUNNING
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
                lifecycle = ActorLifecycle.SHUTDOWN
                terminated.complete(Unit)
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
     * Called when [handle] throws. Default is non-fatal; override to complete replies
     * exceptionally or record failures in an actor-specific way.
     */
    protected open suspend fun onUnhandledCommandFailure(
        command: Command,
        cause: Throwable,
    ) {
        // Default: non-fatal. Concrete actors complete replies or record via observability.
    }

    /** Throws if [lifecycle] is not [ActorLifecycle.RUNNING]. */
    protected fun ensureAccepting() {
        check(lifecycle == ActorLifecycle.RUNNING) {
            "$actorName is not accepting new commands: $lifecycle"
        }
    }

    /**
     * Non-blocking send. When not [ActorLifecycle.RUNNING], throws [ActorMailboxClosedException].
     * When RUNNING, returns the result of [Channel.trySend]; callers must check [ChannelResult.isSuccess]
     * or throw [ActorMailboxFullException] when full.
     */
    protected fun trySend(command: Command): ChannelResult<Unit> {
        if (lifecycle != ActorLifecycle.RUNNING) {
            throw ActorMailboxClosedException(actorName)
        }
        return mailbox.trySend(command)
    }

    /**
     * One-way send. Throws [ActorMailboxClosedException] or [ActorMailboxFullException]
     * if the actor is not accepting or the mailbox is full.
     */
    protected fun send(command: Command) {
        val result = trySend(command)
        if (!result.isSuccess) {
            throw result.exceptionOrNull() ?: ActorMailboxFullException(actorName)
        }
    }

    /**
     * Request/reply: build a command that carries [CompletableDeferred], send it, and await the result.
     * Throws if not accepting or mailbox full.
     */
    protected suspend fun <R> request(build: (CompletableDeferred<R>) -> Command): R {
        ensureAccepting()
        val reply = CompletableDeferred<R>()
        val result = trySend(build(reply))
        if (!result.isSuccess) {
            throw result.exceptionOrNull() ?: ActorMailboxFullException(actorName)
        }
        return reply.await()
    }

    /**
     * Graceful shutdown: transition to [ActorLifecycle.SHUTTING_DOWN], close mailbox, run [preStop],
     * drain buffered commands, then wait for termination. If [timeout] is set and exceeded,
     * cancels the loop job and waits for termination. Idempotent after first caller.
     */
    protected suspend fun shutdownGracefully(timeout: Duration? = null) {
        val shouldStop =
            lifecycleMutex.withLock {
                when (lifecycle) {
                    ActorLifecycle.STARTING,
                    ActorLifecycle.RUNNING,
                    -> {
                        lifecycle = ActorLifecycle.SHUTTING_DOWN
                        true
                    }

                    ActorLifecycle.SHUTTING_DOWN,
                    ActorLifecycle.SHUTDOWN,
                    -> {
                        false
                    }
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

        val completed =
            withTimeoutOrNull(timeout) {
                terminated.await()
                true
            } == true

        if (!completed) {
            loopJob.cancel()
            terminated.await()
        }
    }

    /**
     * Forced shutdown: transition to [ActorLifecycle.SHUTTING_DOWN], close mailbox, run [preStop],
     * cancel the loop job, then wait for termination. Idempotent after first caller.
     */
    protected suspend fun shutdownNow() {
        val shouldStop =
            lifecycleMutex.withLock {
                when (lifecycle) {
                    ActorLifecycle.STARTING,
                    ActorLifecycle.RUNNING,
                    -> {
                        lifecycle = ActorLifecycle.SHUTTING_DOWN
                        true
                    }

                    ActorLifecycle.SHUTTING_DOWN,
                    ActorLifecycle.SHUTDOWN,
                    -> {
                        false
                    }
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

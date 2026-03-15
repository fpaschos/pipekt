package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Base failure type surfaced by actor refs via [Result.failure].
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

class ActorUnavailableException(
    val reason: ActorUnavailableReason,
    actorName: String,
    cause: Throwable? = null,
) : ActorException("$actorName is unavailable", cause)

/**
 * Request/reply did not complete before the timeout elapsed.
 */
class ActorAskTimeoutException(
    actorName: String,
    timeout: Duration,
) : ActorException("$actorName did not reply within $timeout")

/**
 * Actor command handling failed after the command was accepted.
 */
class ActorCommandFailedException(
    actorName: String,
    cause: Throwable,
) : ActorException("$actorName command failed", cause)

/**
 * Generic typed handle to an actor.
 *
 * Callers interact through [tell], [ask], and [shutdown]; they do not access the actor
 * instance or mailbox directly.
 */
interface ActorRef<in Command : Any> {
    val actorName: String

    /**
     * Sends [command] to the target actor without waiting for a reply.
     *
     * Returns [Result.success] when the command was accepted. Returns [Result.failure]
     * with [ActorUnavailableException] when the actor cannot accept the command.
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
 */
suspend fun <Command : Any, Reply> ActorRef<Command>.ask(
    timeout: Duration,
    build: (CompletableDeferred<Reply>) -> Command,
): Result<Reply> {
    val reply = CompletableDeferred<Reply>()
    val enqueue = tell(build(reply))
    if (enqueue.isFailure) {
        return Result.failure(enqueue.exceptionOrNull()!!)
    }

    return try {
        Result.success(withTimeout(timeout) { reply.await() })
    } catch (_: TimeoutCancellationException) {
        val timeoutException = ActorAskTimeoutException(actorName, timeout)
        reply.cancel()
        Result.failure(timeoutException)
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

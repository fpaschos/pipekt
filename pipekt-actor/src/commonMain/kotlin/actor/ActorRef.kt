package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.ConsistentCopyVisibility
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
     * because shutdown or failure dropped it before delivery.
     */
    NOT_DELIVERED,
}

class ActorUnavailable(
    val reason: ActorUnavailableReason,
    label: String,
    cause: Throwable? = null,
) : ActorException("Actor $label is unavailable", cause)

/**
 * Request/reply did not complete before the timeout elapsed.
 */
class ActorAskTimeout(
    label: String,
    timeout: Duration,
) : ActorException("Actor $label did not reply within $timeout")

/**
 * Actor command handling failed after the command was accepted.
 */
class ActorCommandFailed(
    label: String,
    cause: Throwable,
) : ActorException("Actor $label command failed", cause)

/**
 * Termination metadata emitted by watch notifications.
 */
data class ActorTermination(
    val actorName: String,
    val actorLabel: String,
    val cause: Throwable?,
)

/**
 * Generic typed handle to an actor.
 */
interface ActorRef<in Command : Any> : ReplyRef<Command> {
    val name: String

    val label: String

    override fun tell(reply: Command): Result<Unit>

    suspend fun shutdown(timeout: Duration? = null)
}

@ConsistentCopyVisibility
data class DefaultActorRef<Command : Any> internal constructor(
    override val name: String,
    override val label: String,
    internal val runtime: ActorRuntime<Command>,
) : ActorRef<Command> {
    override fun tell(command: Command): Result<Unit> = runtime.send(command)

    override suspend fun shutdown(timeout: Duration?) {
        runtime.shutdown(timeout)
    }
}

/**
 * Universal request/reply helper built on reply-bearing commands.
 */
suspend fun <Command : Any, Reply> ActorRef<Command>.ask(
    timeout: Duration,
    block: (ReplyRef<Reply>) -> Command,
): Result<Reply> {
    val reply = deferredReplyRef<Reply>()
    val command = block(reply)
    val enqueue =
        (this as? DefaultActorRef<Command>)
            ?.runtime
            ?.send(command, reply)
            ?: tell(command)
    if (enqueue.isFailure) {
        return Result.failure(enqueue.exceptionOrNull()!!)
    }

    return try {
        Result.success(withTimeout(timeout) { reply.await() })
    } catch (_: TimeoutCancellationException) {
        val timeoutException = ActorAskTimeout(label, timeout)
        reply.cancel()
        Result.failure(timeoutException)
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

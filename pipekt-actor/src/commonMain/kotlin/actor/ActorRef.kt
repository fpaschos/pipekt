package io.github.fpaschos.pipekt.actor

import io.github.fpaschos.pipekt.actor.runtime.ActorRuntime
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

/**
 * Failure reported when an actor rejects a command before processing it, or when an already
 * accepted command becomes undeliverable because the actor shuts down.
 *
 * @property reason Why the actor was unavailable.
 */
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
 *
 * An [ActorRef] is the public entry point for sending commands to an actor. Request/reply
 * protocols also use `replyTo: ActorRef<Reply>` so actors can answer by sending a normal message
 * back to another actor ref.
 *
 * ### Admission
 * - [tell] is non-suspending.
 * - Commands are accepted only while the actor is running.
 * - Rejection is reported via [Result.failure] with [ActorUnavailable].
 *
 * ### Lifecycle
 * - [shutdown] requests cooperative actor termination and waits for completion.
 * - Actor code must not call [shutdown] on itself; use [ActorContext.stopSelf] instead.
 */
interface ActorRef<in Command : Any> {
    val name: String

    val label: String

    /**
     * Attempts to enqueue [command] for this actor.
     *
     * Returns success only if the actor is currently running and its mailbox can accept the
     * command.
     */
    fun tell(command: Command): Result<Unit>

    /**
     * Requests cooperative shutdown and waits for termination.
     *
     * When [timeout] is not `null`, it bounds mailbox draining after shutdown begins; it does not
     * bound total termination time or interrupt the command currently running on the actor loop.
     */
    suspend fun shutdown(timeout: Duration? = null)
}

internal interface AskCapableActorRef<in Command : Any> : ActorRef<Command> {
    fun sendForAsk(
        command: Command,
        askReply: Any,
    ): Result<Unit>
}

@ConsistentCopyVisibility
data class DefaultActorRef<Command : Any> internal constructor(
    override val name: String,
    override val label: String,
    internal val runtime: ActorRuntime<Command>,
) : AskCapableActorRef<Command> {
    override fun tell(command: Command): Result<Unit> = runtime.send(command)

    override fun sendForAsk(
        command: Command,
        askReply: Any,
    ): Result<Unit> = runtime.send(command, askReply as AskReplyHandle)

    override suspend fun shutdown(timeout: Duration?) {
        runtime.shutdown(timeout)
    }
}

/**
 * Universal request/reply helper built on reply-bearing commands.
 *
 * This helper creates a temporary one-shot [ActorRef], builds a command using [block], sends it,
 * and then waits for the first reply until [timeout] elapses.
 *
 * Failure mapping:
 * - enqueue rejection is returned directly
 * - timeout becomes [ActorAskTimeout]
 * - handler failure after acceptance may surface as [ActorCommandFailed]
 * - shutdown/failure before delivery may surface as [ActorUnavailable]
 *
 * ### `block` behavior
 * - Invoked synchronously in the caller's coroutine before enqueue.
 * - Must construct exactly one command that embeds the provided reply actor ref.
 */
suspend fun <Command : Any, Reply : Any> ActorRef<Command>.ask(
    timeout: Duration,
    block: (ActorRef<Reply>) -> Command,
): Result<Reply> {
    val reply = deferredReplyActorRef<Reply>()
    val command = block(reply)
    val enqueue =
        (this as? AskCapableActorRef<Command>)
            ?.sendForAsk(command, reply)
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

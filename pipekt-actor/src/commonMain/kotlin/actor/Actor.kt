package io.github.fpaschos.pipekt.actor

import io.github.fpaschos.pipekt.actor.runtime.ActorRuntime
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext

/**
 * Base actor behavior.
 *
 * An [Actor] processes commands sequentially on a single coroutine ("the actor loop"). Concrete
 * actors may keep internal mutable state, but that state must only be accessed from actor callbacks
 * executed on the actor loop coroutine.
 *
 * Commands are delivered via a mailbox backed by a Kotlin [Channel]. The mailbox capacity is
 * configured by [capacity]. Sending is non-suspending (fails fast when the mailbox is full or the
 * actor is not running), so callers should be prepared to handle backpressure/failures.
 *
 * ### Concurrency
 * - The library invokes [postStart], [handle], [preStop], [postStop] on the actor loop coroutine.
 * - Calls may be suspended; cancellation will propagate like normal coroutine cancellation.
 *
 * ### Failure
 * - If [handle] throws a non-cancellation exception, the actor transitions to shutdown and queued
 *   commands are treated as undelivered.
 * - If [handle] is cancelled, cancellation is rethrown and the actor shuts down.
 *
 * See [ActorContext.guardActorAccess] for a runtime guard and usage guidance.
 */
abstract class Actor<Command : Any>(
    internal val capacity: Int = Channel.BUFFERED,
) {
    /**
     * Called once after the actor loop is created and before the actor starts processing commands.
     *
     * Use this for initialization that must run on the actor loop coroutine (e.g., initializing
     * mutable state that must remain actor-confined).
     *
     * If this throws (or the coroutine is cancelled), the actor fails to start and [spawn] fails.
     * In that case, no running actor is returned to the caller.
     */
    open suspend fun postStart(ctx: ActorContext<Command>) {}

    /**
     * Handles a single [command].
     *
     * Commands are processed sequentially in the order they are received from the mailbox (subject
     * to shutdown/stop semantics).
     *
     * ### Failure
     * If this throws (other than cancellation), the actor will:
     * - invoke [onFailure]
     * - fail any in-flight "ask" reply
     * - stop the actor and treat remaining queued commands as undelivered via [onUndelivered]
     *
     * If this throws [kotlinx.coroutines.CancellationException], the cancellation is treated as
     * actor shutdown: the in-flight "ask" reply (if any) fails with [ActorUnavailable], and the
     * exception is rethrown to cancel the actor loop.
     *
     * @param ctx The actor context for accessing [ActorRef] utilities and lifecycle operations.
     * @param command The command to process.
     */
    abstract suspend fun handle(
        ctx: ActorContext<Command>,
        command: Command,
    )

    /**
     * Called once when the actor begins shutting down, before draining/dropping remaining commands.
     *
     * This callback is executed in a non-cancellable context so you can perform best-effort cleanup.
     * Any exception thrown here is captured as the actor's terminal cause (if one is not already set),
     * but it does not prevent termination.
     */
    open suspend fun preStop(ctx: ActorContext<Command>) {}

    /**
     * Called once after the actor loop finishes and termination is published to watchers.
     *
     * This callback is executed in a non-cancellable context. Exceptions are captured as terminal
     * cause (if one is not already set), but they do not prevent termination.
     */
    open suspend fun postStop(ctx: ActorContext<Command>) {}

    /**
     * Called when [handle] throws a non-cancellation exception.
     *
     * This is intended for side effects like logging/metrics. Throwing from this callback does not
     * prevent shutdown.
     */
    open suspend fun onFailure(
        ctx: ActorContext<Command>,
        command: Command,
        cause: Throwable,
    ) {
    }

    /**
     * Called for commands that were accepted by the caller but could not be delivered/processed.
     *
     * Typical reasons are:
     * - the actor is shutting down and the mailbox is closed
     * - the mailbox is full and the send operation fails fast
     * - the actor failed while there were still queued commands
     *
     * This callback is invoked on the actor loop coroutine while shutting down. It must be fast and
     * must not assume the actor is still running.
     */
    open fun onUndelivered(
        ctx: ActorContext<Command>,
        command: Command,
        reason: ActorUnavailableReason,
    ) {
    }
}

internal val nextActorInstanceId = atomic(0L)

/**
 * Spawns a new actor and suspends until it has started.
 *
 * The actor runtime derives its ownership from the caller's current coroutine context. `spawn(...)`
 * creates a dedicated child [kotlinx.coroutines.SupervisorJob] under the caller's current [kotlinx.coroutines.Job]
 * and runs the actor loop inside that child scope. If [dispatcher] is provided, the actor loop uses
 * that dispatcher; otherwise it inherits the caller's dispatcher.
 *
 * ### Concurrency
 * - The actor processes commands sequentially on a single coroutine (actor loop).
 * - Actor-confined mutable state must only be accessed from [Actor] callbacks.
 * - Actor-owned support coroutines (for example internal timers) are launched in the same owned
 *   child scope as the actor loop.
 * - The owned child scope uses supervisor semantics: failure of one actor-owned child coroutine does
 *   not automatically cancel sibling actor-owned coroutines.
 * - Cancellation from the caller's coroutine context still propagates into the actor because the
 *   owned child scope remains a child of the caller's job.
 *
 * ### Failure and cancellation
 * - If [factory] throws, this function fails and no actor is started.
 * - If [Actor.postStart] throws, or the actor loop is cancelled during startup, this function
 *   fails and no running actor is returned.
 * - If the caller's parent job is cancelled after startup, the actor is cancelled as part of normal
 *   structured concurrency.
 *
 * ### Example
 * ```kotlin
 * sealed interface Cmd { data object Ping : Cmd }
 *
 * class Pinger : Actor<Cmd>() {
 *   override suspend fun handle(ctx: ActorContext<Cmd>, command: Cmd) {
 *     when (command) { Cmd.Ping -> println("pong") }
 *   }
 * }
 *
 * val ref = spawn("pinger") { Pinger() }
 * ref.tell(Cmd.Ping)
 * ```
 */
suspend fun <Command : Any> spawn(
    name: String,
    dispatcher: CoroutineDispatcher? = null,
    factory: () -> Actor<Command>,
): ActorRef<Command> {
    val actor = factory()
    val parentScope = CoroutineScope(currentCoroutineContext())
    val id = nextActorInstanceId.incrementAndGet()
    val label = "$name#$id"
    val runtime =
        ActorRuntime(name = name, label = label, parentScope = parentScope, dispatcher = dispatcher, actor = actor)
    runtime.start(actor)
    runtime.awaitStarted()
    return runtime.ref
}

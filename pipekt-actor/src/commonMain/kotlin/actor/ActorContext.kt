package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Loop-confined runtime capability surface exposed to concrete actors.
 *
 * The context is only valid while executing actor callbacks on the actor loop coroutine.
 * Although the runtime only passes it into loop-owned callbacks, this is still a normal object
 * reference and actor code can leak it to other coroutines. Context operations therefore enforce
 * loop confinement at runtime instead of relying on usage discipline alone.
 */
interface ActorContext<Command : Any> {
    val name: String

    val label: String

    val self: ActorRef<Command>

    /**
     * Guards actor-loop-only access.
     *
     * This fails unless the current coroutine is the actor loop coroutine for this actor.
     *
     * Use it in helper methods that mutate actor-owned state but do not otherwise touch context
     * APIs.
     *
     * Inline suspend calls from actor callbacks are safe because they stay on the same logical
     * actor execution path. What this does *not* guarantee is safety for separately launched
     * coroutines. A `launch { ... }`, callback, or other detached async path must not mutate actor
     * state directly; it must route work back through the actor mailbox instead.
     *
     *
     * Example of an invalid callback path:
     * ```kotlin
     * class CallbackActor : Actor<CallbackCommand>() {
     *     private var count: Int = 0
     *
     *     override suspend fun handle(
     *         ctx: ActorContext<CallbackCommand>,
     *         command: CallbackCommand,
     *     ) {
     *         registerCallback {
     *             ctx.guardActorAccess() // This fails.
     *             count += 1
     *         }
     *     }
     *
     *     private fun registerCallback(block: () -> Unit) {
     *         // Calls block later from a different coroutine or thread.
     *     }
     * }
     * ```
     */
    suspend fun guardActorAccess()

    /**
     * Registers a one-shot termination watch for [actor].
     *
     * This method is loop-confined and fails if called from outside the actor loop coroutine,
     * even if the context reference was originally obtained from a valid actor callback.
     */
    suspend fun watch(
        actor: ActorRef<*>,
        onTerminated: (ActorTermination) -> Command,
    )

    /**
     * Requests cooperative shutdown for the current actor without waiting for termination.
     */
    suspend fun stopSelf(timeout: Duration? = null)
}

internal class DefaultActorContext<Command : Any>(
    override val name: String,
    override val label: String,
    override val self: ActorRef<Command>,
    private val runtime: ActorRuntime<Command>,
) : ActorContext<Command> {
    private val watchMappers = mutableMapOf<Long, Pair<ActorRuntime<*>, (ActorTermination) -> Command>>()
    private val watchedActors = mutableMapOf<ActorRuntime<*>, Long>()
    private var nextWatchToken: Long = 0

    override suspend fun guardActorAccess() {
        check(currentCoroutineContext()[ActorLoopContext]?.runtime === runtime) {
            "This method must only be called only within actor $label coroutine."
        }
    }

    override suspend fun watch(
        actor: ActorRef<*>,
        onTerminated: (ActorTermination) -> Command,
    ) {
        guardActorAccess()

        val target =
            actor as? DefaultActorRef<*>
                ?: error("Only actor refs created by this runtime can be watched.")

        check(target.runtime !== runtime) { "Actor $label cannot watch itself." }
        check(runtime.canRegisterWatches()) { "Actor $label cannot register new watches while shutting down." }
        if (watchedActors.containsKey(target.runtime)) {
            return
        }

        val token = ++nextWatchToken
        watchMappers[token] = target.runtime to onTerminated
        watchedActors[target.runtime] = token
        target.runtime.registerWatcher(runtime, token)
    }

    override suspend fun stopSelf(timeout: Duration?) {
        guardActorAccess()
        runtime.requestStop(timeout)
    }

    internal fun dispatchWatchNotification(
        token: Long,
        termination: ActorTermination,
    ): Command? {
        val (target, mapper) = watchMappers.remove(token) ?: return null
        watchedActors.remove(target)
        return mapper(termination)
    }
}

internal class ActorLoopContext(
    val runtime: ActorRuntime<*>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActorLoopContext>
}

package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Stable actor-local identity for a timer.
 *
 * Reusing the same key replaces the existing timer for that logical purpose. Keys are scoped to
 * one actor instance; different actors may reuse the same [value] without interacting.
 */
@JvmInline
value class TimerKey(
    val value: String,
)

/**
 * Loop-confined timer capability for actor self-messages.
 *
 * Timers are owned by the actor runtime rather than by detached coroutine jobs. Starting a timer
 * with an existing [TimerKey] replaces the previous timer for that key, and stale messages from
 * the replaced timer are suppressed even if they were already queued internally.
 *
 * All methods are loop-confined and fail if called outside the actor loop coroutine.
 */
interface ActorTimers<Command : Any> {
    /**
     * Schedules [command] to be sent once to the current actor after [delay].
     *
     * Reusing [key] replaces the existing timer for that key. A [delay] of [ZERO] bypasses timer
     * scheduling and immediately enqueues a normal self-message using the actor's standard
     * admission rules.
     */
    suspend fun once(
        key: TimerKey,
        delay: Duration,
        command: Command,
    )

    /**
     * Starts or replaces a fixed-delay repeating self-message timer.
     *
     * After each firing, the next delay starts counting from that firing. The runtime does not
     * attempt fixed-rate catch-up behavior. Reusing [key] replaces the existing timer for that key.
     */
    suspend fun repeated(
        key: TimerKey,
        interval: Duration,
        command: Command,
    )

    /**
     * Cancels the active timer for [key].
     *
     * Returns `true` when a timer was active and has been canceled. Returns `false` when no timer
     * was active for [key]. Calling this for a previously canceled key is safe.
     */
    suspend fun cancel(key: TimerKey): Boolean

    /**
     * Cancels all active timers for the current actor.
     *
     * Timers are also canceled automatically when actor shutdown begins.
     */
    suspend fun cancelAll()
}

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
     * Actor-owned keyed timers for delayed and repeating self-messages.
     */
    val timers: ActorTimers<Command>

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
    override val timers: ActorTimers<Command> = DefaultActorTimers(runtime)

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

internal class DefaultActorTimers<Command : Any>(
    private val runtime: ActorRuntime<Command>,
) : ActorTimers<Command> {
    override suspend fun once(
        key: TimerKey,
        delay: Duration,
        command: Command,
    ) {
        runtime.scheduleTimer(
            key = key,
            timerDelay = delay,
            command = command,
            mode = TimerMode.Once,
        )
    }

    override suspend fun repeated(
        key: TimerKey,
        interval: Duration,
        command: Command,
    ) {
        runtime.scheduleTimer(
            key = key,
            timerDelay = interval,
            command = command,
            mode = TimerMode.Repeated,
        )
    }

    override suspend fun cancel(key: TimerKey): Boolean = runtime.cancelTimer(key)

    override suspend fun cancelAll() {
        runtime.cancelAllTimers()
    }
}

internal enum class TimerMode {
    Once,
    Repeated,
}

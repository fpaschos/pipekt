package io.github.fpaschos.pipekt.actor

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Lifecycle states for the shared actor infrastructure.
 */
internal enum class ActorLifecycle {
    STARTING,
    RUNNING,
    SHUTTING_DOWN,
    SHUTDOWN,
}

internal val nextActorInstanceId = atomic(0L)

private data class CommandEnvelope<Command : Any>(
    val command: Command,
    val askReply: AskReplyHandle? = null,
)

private sealed interface SystemEvent<out Command : Any> {
    data class Stop(
        val timeout: Duration?,
    ) : SystemEvent<Nothing>

    data class WatchNotification(
        val token: Long,
        val termination: ActorTermination,
    ) : SystemEvent<Nothing>

    data class TimerFired<Command : Any>(
        val key: TimerKey,
        val generation: Long,
        val command: Command,
    ) : SystemEvent<Command>
}

private sealed interface WatchState {
    data class Active(
        val listeners: Map<ActorRuntime<*>, Long>,
    ) : WatchState

    data class Terminated(
        val termination: ActorTermination,
    ) : WatchState
}

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
     * - invoke [onCommandFailure]
     * - fail any in-flight "ask" reply
     * - stop the actor and treat remaining queued commands as undelivered via [onUndeliveredCommand]
     *
     * If this throws [CancellationException], the cancellation is treated as actor shutdown:
     * the in-flight "ask" reply (if any) fails with [ActorUnavailable], and the exception is
     * rethrown to cancel the actor loop.
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
    open suspend fun onCommandFailure(
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
    open fun onUndeliveredCommand(
        ctx: ActorContext<Command>,
        command: Command,
        reason: ActorUnavailableReason,
    ) {
    }
}

internal class ActorRuntime<Command : Any>(
    val name: String,
    val label: String,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher?,
    actor: Actor<Command>,
) {
    private val scope = createActorScope(parentScope, name, dispatcher)
    private val mailbox = Channel<CommandEnvelope<Command>>(actor.capacity)
    private val systemQueue = Channel<SystemEvent<Command>>(capacity = Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()
    private val lifecycle = atomic(ActorLifecycle.STARTING)
    private val terminalCause = atomic<Throwable?>(null)
    private val watchState = atomic<WatchState>(WatchState.Active(emptyMap()))

    // Stop can be requested while STARTING; we detect it after postStart and fail startup consistently.
    private val stopRequest = atomic<Any?>(UNSET_STOP_TIMEOUT)
    private val preStopExecuted = atomic(false)

    // Timer generations make replacement and cancellation stronger than plain job cancellation:
    // even if an old timer already enqueued a fire event, stale generations are dropped later.
    private val activeTimers = mutableMapOf<TimerKey, ActiveTimer>()
    private val nextTimerGeneration = mutableMapOf<TimerKey, Long>()

    private lateinit var selfRef: DefaultActorRef<Command>
    internal lateinit var loopJob: Job
        private set

    fun attachRef(ref: DefaultActorRef<Command>) {
        check(!::selfRef.isInitialized) { "Actor runtime for $label already has a ref." }
        selfRef = ref
    }

    fun canRegisterWatches(): Boolean = lifecycle.value != ActorLifecycle.SHUTTING_DOWN && lifecycle.value != ActorLifecycle.SHUTDOWN

    fun canScheduleTimers(): Boolean = lifecycle.value != ActorLifecycle.SHUTTING_DOWN && lifecycle.value != ActorLifecycle.SHUTDOWN

    fun start(actor: Actor<Command>) {
        check(!::loopJob.isInitialized) { "Actor runtime for $label already started." }
        val ctx = DefaultActorContext(name = name, label = label, self = selfRef, runtime = this)

        loopJob =
            scope.launch(CoroutineName(label) + ActorLoopContext(this@ActorRuntime)) {
                var keepRunning = true

                try {
                    actor.postStart(ctx)

                    if (stopRequest.value !== UNSET_STOP_TIMEOUT) {
                        lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                        val cause = CancellationException("$label was stopped during startup")
                        terminalCause.compareAndSet(null, cause)
                        ActorLogger.startupFailed(label = label, cause = cause)
                        notifyStartFail(cause)
                        cancelAllActiveTimers()
                        mailbox.close()
                        runPreStop(actor, ctx)
                        dropPendingCommands(actor, ctx)
                        keepRunning = false
                    } else {
                        lifecycle.value = ActorLifecycle.RUNNING
                        ActorLogger.started(label = label)
                        notifyStart()
                    }

                    while (keepRunning) {
                        // Prioritize system events (stop/watch) to keep shutdown responsive under mailbox load.
                        val immediateSystem = systemQueue.tryReceive().getOrNull()
                        if (immediateSystem != null) {
                            keepRunning = handleSystemEvent(actor, ctx, immediateSystem)
                            continue
                        }

                        select {
                            systemQueue.onReceiveCatching { result ->
                                val event = result.getOrNull() ?: return@onReceiveCatching
                                keepRunning = handleSystemEvent(actor, ctx, event)
                            }

                            mailbox.onReceiveCatching { result ->
                                val envelope =
                                    result.getOrElse {
                                        lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                                        keepRunning = false
                                        return@onReceiveCatching
                                    }
                                handleCommand(actor, ctx, envelope)
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    terminalCause.compareAndSet(null, ce)
                    lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                    cancelAllActiveTimers()
                    mailbox.close(ce)
                    if (!started.isCompleted) {
                        ActorLogger.startupFailed(label = label, cause = ce)
                    }
                    notifyStartFail(ce)
                    runPreStop(actor, ctx)
                    dropPendingCommands(actor, ctx)
                    throw ce
                } catch (t: Throwable) {
                    terminalCause.compareAndSet(null, t)
                    lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                    cancelAllActiveTimers()
                    mailbox.close(t)
                    if (!started.isCompleted) {
                        ActorLogger.startupFailed(label = label, cause = t)
                    }
                    notifyStartFail(t)
                    runPreStop(actor, ctx)
                    dropPendingCommands(actor, ctx)
                } finally {
                    lifecycle.value = ActorLifecycle.SHUTDOWN
                    try {
                        runPostStop(actor, ctx)
                    } finally {
                        ActorLogger.stopped(label = label, cause = terminalCause.value)
                        publishTermination()
                        notifyTermination()
                        systemQueue.close()
                        cancelOwnedScope()
                    }
                }
            }
    }

    fun send(
        command: Command,
        askReply: AskReplyHandle? = null,
    ): Result<Unit> {
        if (lifecycle.value != ActorLifecycle.RUNNING) {
            return Result.failure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.ACTOR_CLOSED,
                    label = label,
                ),
            )
        }

        val sendResult = mailbox.trySend(CommandEnvelope(command = command, askReply = askReply))
        if (sendResult.isSuccess) {
            return Result.success(Unit)
        }
        return sendResult.toActorSendResult(label)
    }

    suspend fun shutdown(timeout: Duration?) {
        check(currentCoroutineContext()[Job] !== loopJob) {
            "Actor $label cannot call shutdown() on itself; use ctx.stopSelf()."
        }
        requestStop(timeout)
        terminated.await()
        cancelOwnedScope()
    }

    fun requestStop(timeout: Duration?) {
        if (lifecycle.value == ActorLifecycle.SHUTDOWN) {
            return
        }
        if (stopRequest.compareAndSet(UNSET_STOP_TIMEOUT, timeout)) {
            ActorLogger.stopRequested(label = label, timeout = timeout)
            systemQueue.trySend(SystemEvent.Stop(timeout))
        }
    }

    suspend fun awaitStarted() {
        started.await()
    }

    fun registerWatcher(
        watcher: ActorRuntime<*>,
        token: Long,
    ) {
        while (true) {
            when (val state = watchState.value) {
                is WatchState.Active -> {
                    if (state.listeners.containsKey(watcher)) {
                        return
                    }
                    val updated = WatchState.Active(state.listeners + (watcher to token))
                    if (watchState.compareAndSet(state, updated)) {
                        return
                    }
                }

                is WatchState.Terminated -> {
                    // Linearize watch registration against termination: if already terminated, notify immediately.
                    watcher.enqueueWatchNotification(token, state.termination)
                    return
                }
            }
        }
    }

    private fun enqueueWatchNotification(
        token: Long,
        termination: ActorTermination,
    ) {
        systemQueue.trySend(SystemEvent.WatchNotification(token, termination))
    }

    private suspend fun handleSystemEvent(
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
        event: SystemEvent<Command>,
    ): Boolean =
        when (event) {
            is SystemEvent.Stop -> {
                lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                cancelAllActiveTimers()
                mailbox.close()
                runPreStop(actor, ctx)

                val drained =
                    if (event.timeout == null) {
                        drainMailbox(actor, ctx)
                        true
                    } else {
                        withTimeoutOrNull(event.timeout) {
                            drainMailbox(actor, ctx)
                            true
                        } == true
                    }

                if (!drained) {
                    dropPendingCommands(actor, ctx)
                }
                false
            }

            is SystemEvent.WatchNotification -> {
                val mapped = ctx.dispatchWatchNotification(event.token, event.termination) ?: return true
                handleCommand(actor, ctx, CommandEnvelope(mapped))
                true
            }

            is SystemEvent.TimerFired -> {
                val timer = activeTimers[event.key] ?: return true
                // Timer replacement/cancellation is linearized by generation. A queued fire from an
                // older timer instance is ignored here even if its coroutine managed to enqueue it.
                if (timer.generation != event.generation) {
                    return true
                }
                if (timer.mode == TimerMode.Once) {
                    activeTimers.remove(event.key)
                }
                handleCommand(actor, ctx, CommandEnvelope(event.command))
                true
            }
        }

    private suspend fun handleCommand(
        actor: Actor<Command>,
        ctx: ActorContext<Command>,
        envelope: CommandEnvelope<Command>,
    ) {
        try {
            actor.handle(ctx, envelope.command)
        } catch (ce: CancellationException) {
            envelope.askReply?.completeFailure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.ACTOR_CLOSED,
                    label = ctx.label,
                    cause = ce,
                ),
            )
            throw ce
        } catch (t: Throwable) {
            terminalCause.compareAndSet(null, t)
            ActorLogger.commandFailed(label = label, cause = t)
            actor.onCommandFailure(ctx, envelope.command, t)
            envelope.askReply?.completeFailure(ActorCommandFailed(ctx.label, t))
            mailbox.close(t)
            dropPendingCommands(actor, ctx)
            throw t
        }
    }

    private suspend fun drainMailbox(
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
    ) {
        while (true) {
            val envelope = mailbox.receiveCatching().getOrNull() ?: return
            handleCommand(actor, ctx, envelope)
        }
    }

    private fun dropPendingCommands(
        actor: Actor<Command>,
        ctx: ActorContext<Command>,
    ) {
        while (true) {
            val envelope = mailbox.tryReceive().getOrNull() ?: break
            actor.onUndeliveredCommand(ctx, envelope.command, ActorUnavailableReason.NOT_DELIVERED)
            envelope.askReply?.completeFailure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.NOT_DELIVERED,
                    label = ctx.label,
                ),
            )
        }
    }

    private suspend fun runPreStop(
        actor: Actor<Command>,
        ctx: ActorContext<Command>,
    ) {
        if (!preStopExecuted.compareAndSet(expect = false, update = true)) {
            return
        }
        try {
            withContext(NonCancellable) {
                actor.preStop(ctx)
            }
        } catch (t: Throwable) {
            terminalCause.compareAndSet(null, t)
        }
    }

    private suspend fun runPostStop(
        actor: Actor<Command>,
        ctx: ActorContext<Command>,
    ) {
        try {
            withContext(NonCancellable) {
                actor.postStop(ctx)
            }
        } catch (t: Throwable) {
            terminalCause.compareAndSet(null, t)
        }
    }

    private fun notifyStart() {
        started.complete(Unit)
    }

    private fun notifyStartFail(cause: Throwable) {
        if (!started.isCompleted) {
            started.completeExceptionally(cause)
        }
    }

    private fun notifyTermination() {
        if (!terminated.isCompleted) {
            terminated.complete(Unit)
        }
    }

    private fun publishTermination() {
        val termination =
            ActorTermination(
                actorName = name,
                actorLabel = label,
                cause = terminalCause.value,
            )

        while (true) {
            when (val state = watchState.value) {
                is WatchState.Active -> {
                    if (watchState.compareAndSet(state, WatchState.Terminated(termination))) {
                        state.listeners.forEach { (watcher, token) ->
                            watcher.enqueueWatchNotification(token, termination)
                        }
                        return
                    }
                }

                is WatchState.Terminated -> {
                    return
                }
            }
        }
    }

    private fun cancelOwnedScope() {
        if (scope.coroutineContext[OwnedActorScope.Key] != null) {
            scope.coroutineContext.job.cancel()
        }
    }

    suspend fun scheduleTimer(
        key: TimerKey,
        timerDelay: Duration,
        command: Command,
        mode: TimerMode,
    ) {
        guardActorLoop()
        check(canScheduleTimers()) { "Actor $label cannot schedule timers while shutting down." }
        require(timerDelay >= Duration.ZERO) { "Timer delay must be non-negative." }

        val generation = (nextTimerGeneration[key] ?: 0L) + 1L
        nextTimerGeneration[key] = generation
        activeTimers.remove(key)?.job?.cancel()
        activeTimers[key] =
            ActiveTimer(
                generation = generation,
                mode = mode,
                job =
                    if (mode == TimerMode.Once && timerDelay == Duration.ZERO) {
                        // Zero-delay still routes through the internal timer queue instead of
                        // self.tell(). That keeps startup-time scheduling valid before RUNNING and
                        // preserves stale-generation suppression.
                        scope.launch {
                            enqueueTimerFire(key = key, generation = generation, command = command)
                        }
                    } else {
                        launchTimerJob(
                            key = key,
                            generation = generation,
                            timerDelay = timerDelay,
                            command = command,
                            mode = mode,
                        )
                    },
            )
    }

    suspend fun cancelTimer(key: TimerKey): Boolean {
        guardActorLoop()
        return activeTimers.remove(key)?.job?.let { job ->
            job.cancel()
            true
        } ?: false
    }

    suspend fun cancelAllTimers() {
        guardActorLoop()
        cancelAllActiveTimers()
    }

    private suspend fun guardActorLoop() {
        check(currentCoroutineContext()[ActorLoopContext]?.runtime === this) {
            "This method must only be called only within actor $label coroutine."
        }
    }

    private fun cancelAllActiveTimers() {
        // Shutdown must detach timers before the runtime finishes draining work so no timer
        // outlives the actor or reintroduces commands during teardown.
        activeTimers.values.forEach { timer -> timer.job.cancel() }
        activeTimers.clear()
    }

    private fun launchTimerJob(
        key: TimerKey,
        generation: Long,
        timerDelay: Duration,
        command: Command,
        mode: TimerMode,
    ): Job =
        scope.launch {
            when (mode) {
                TimerMode.Once -> {
                    delay(timerDelay)
                    enqueueTimerFire(key = key, generation = generation, command = command)
                }

                TimerMode.Repeated -> {
                    while (isActive) {
                        delay(timerDelay)
                        enqueueTimerFire(key = key, generation = generation, command = command)
                    }
                }
            }
        }

    private fun enqueueTimerFire(
        key: TimerKey,
        generation: Long,
        command: Command,
    ) {
        systemQueue.trySend(
            SystemEvent.TimerFired(
                key = key,
                generation = generation,
                command = command,
            ),
        )
    }
}

private data class ActiveTimer(
    val generation: Long,
    val mode: TimerMode,
    val job: Job,
)

private data object OwnedActorScope :
    AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<OwnedActorScope>
}

private fun ChannelResult<Unit>.toActorSendResult(label: String): Result<Unit> =
    when {
        isSuccess -> {
            Result.success(Unit)
        }

        isClosed -> {
            Result.failure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.ACTOR_CLOSED,
                    label = label,
                    cause = exceptionOrNull(),
                ),
            )
        }

        else -> {
            Result.failure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.MAILBOX_FULL,
                    label = label,
                    cause = exceptionOrNull(),
                ),
            )
        }
    }

private fun createActorScope(
    parentScope: CoroutineScope,
    name: String,
    dispatcher: CoroutineDispatcher?,
): CoroutineScope {
    val parentContext = parentScope.coroutineContext
    val childJob = SupervisorJob(parentContext[Job])
    var scopeContext = parentContext + childJob + CoroutineName(name) + OwnedActorScope
    if (dispatcher != null) {
        scopeContext += dispatcher
    }
    return CoroutineScope(scopeContext)
}

private object UnsetStopTimeout

private val UNSET_STOP_TIMEOUT: Any = UnsetStopTimeout

/**
 * Spawns a new actor and suspends until it has started.
 *
 * The actor runs in a child coroutine of the caller's current coroutine context. If [dispatcher]
 * is provided, the actor loop is executed on that dispatcher; otherwise it inherits the caller's
 * dispatcher.
 *
 * ### Concurrency
 * - The actor processes commands sequentially on a single coroutine (actor loop).
 * - Actor-confined mutable state must only be accessed from [Actor] callbacks.
 *
 * ### Failure and cancellation
 * - If [factory] throws, this function fails and no actor is started.
 * - If [Actor.postStart] throws, or the actor loop is cancelled during startup, this function
 *   fails and no running actor is returned.
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
    val ref = DefaultActorRef(name = name, label = label, runtime = runtime)
    runtime.attachRef(ref)
    runtime.start(actor)
    runtime.awaitStarted()
    return ref
}

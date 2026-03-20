package io.github.fpaschos.pipekt.actor.runtime

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorCommandFailed
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.ActorLogger
import io.github.fpaschos.pipekt.actor.ActorLoopContext
import io.github.fpaschos.pipekt.actor.ActorTermination
import io.github.fpaschos.pipekt.actor.ActorUnavailable
import io.github.fpaschos.pipekt.actor.ActorUnavailableReason
import io.github.fpaschos.pipekt.actor.AskReplyHandle
import io.github.fpaschos.pipekt.actor.DefaultActorContext
import io.github.fpaschos.pipekt.actor.DefaultActorRef
import io.github.fpaschos.pipekt.actor.OverflowStrategy
import io.github.fpaschos.pipekt.actor.TimerKey
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

internal class ActorRuntime<Command : Any>(
    val name: String,
    val label: String,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher?,
    actor: Actor<Command>,
) {
    private val scope = createActorScope(parentScope, name, dispatcher)
    private val mailbox = Channel<CommandEnvelope<Command>>(actor.capacity)
    private val overflowStrategy = actor.overflowStrategy
    private val systemQueue = Channel<SystemEvent<Command>>(capacity = Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()
    private val lifecycle = atomic(ActorLifecycle.STARTING)
    private val terminalCause = atomic<Throwable?>(null)
    private val watchState = atomic<WatchState>(WatchState.Active(emptySet()))

    // Stop can be requested while STARTING; we detect it after postStart and fail startup consistently.
    private val stopRequest = atomic<Any?>(UNSET_STOP_TIMEOUT)
    private val preStopExecuted = atomic(false)

    // Timer generations make replacement and cancellation stronger than plain job cancellation:
    // even if an old timer already enqueued a fire event, stale generations are dropped later.
    private val activeTimers = mutableMapOf<TimerKey, ActiveTimer>()
    private val nextTimerGeneration = mutableMapOf<TimerKey, Long>()

    internal val ref: DefaultActorRef<Command> = DefaultActorRef(name = name, label = label, runtime = this)
    internal lateinit var loopJob: Job
        private set

    fun canRegisterWatches(): Boolean = lifecycle.value != ActorLifecycle.SHUTTING_DOWN && lifecycle.value != ActorLifecycle.SHUTDOWN

    fun canScheduleTimers(): Boolean = lifecycle.value != ActorLifecycle.SHUTTING_DOWN && lifecycle.value != ActorLifecycle.SHUTDOWN

    fun start(actor: Actor<Command>) {
        check(!::loopJob.isInitialized) { "Actor runtime for $label already started." }
        val ctx = DefaultActorContext(name = name, label = label, self = ref, runtime = this)

        loopJob =
            scope.launch(CoroutineName(label) + ActorLoopContext(this@ActorRuntime)) {
                var keepRunning = true

                try {
                    actor.postStart(ctx)

                    if (stopRequest.value !== UNSET_STOP_TIMEOUT) {
                        val cause = CancellationException("$label was stopped during startup")
                        abortActor(actor, ctx, cause = cause, mailboxCause = null)
                        keepRunning = false
                    } else {
                        publishStarted()
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
                    abortActor(actor, ctx, cause = ce, mailboxCause = ce)
                    throw ce
                } catch (t: Throwable) {
                    abortActor(actor, ctx, cause = t, mailboxCause = t)
                } finally {
                    lifecycle.value = ActorLifecycle.SHUTDOWN
                    try {
                        runPostStop(actor, ctx)
                    } finally {
                        ActorLogger.stopped(label = label, cause = terminalCause.value)
                        publishTermination()
                        notifyTermination()
                        systemQueue.close()
                        scope.cancel()
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
        return sendResult.toActorSendResult(label, overflowStrategy)
    }

    suspend fun shutdown(timeout: Duration?) {
        check(currentCoroutineContext()[Job] !== loopJob) {
            "Actor $label cannot call shutdown() on itself; use ctx.stopSelf()."
        }
        requestStop(timeout)
        terminated.await()
        scope.cancel()
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
    ) {
        while (true) {
            when (val state = watchState.value) {
                is WatchState.Active -> {
                    if (state.listeners.contains(watcher)) {
                        return
                    }
                    val updated = WatchState.Active(state.listeners + watcher)
                    if (watchState.compareAndSet(state, updated)) {
                        return
                    }
                }

                is WatchState.Terminated -> {
                    // Linearize registration against termination: once a target is already terminated,
                    // the watcher can be notified immediately using the watched runtime identity.
                    watcher.enqueueWatchNotification(this, state.termination)
                    return
                }
            }
        }
    }

    private fun enqueueWatchNotification(
        watched: ActorRuntime<*>,
        termination: ActorTermination,
    ) {
        // Watch delivery bypasses the user mailbox so mailbox pressure cannot drop lifecycle events.
        systemQueue.trySend(SystemEvent.WatchNotification(watched, termination))
    }

    private suspend fun handleSystemEvent(
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
        event: SystemEvent<Command>,
    ): Boolean =
        when (event) {
            is SystemEvent.Stop -> handleStop(actor, ctx, event.timeout)

            is SystemEvent.WatchNotification -> {
                val mapped = ctx.dispatchWatchNotification(event.watched, event.termination) ?: return true
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
            actor.onFailure(ctx, envelope.command, t)
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
            actor.onUndelivered(ctx, envelope.command, ActorUnavailableReason.NOT_DELIVERED)
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
                        state.listeners.forEach { watcher ->
                            watcher.enqueueWatchNotification(this, termination)
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

    /**
     * Publishes the transition from startup into normal command admission.
     */
    private fun publishStarted() {
        lifecycle.value = ActorLifecycle.RUNNING
        ActorLogger.started(label = label)
        notifyStart()
    }

    /**
     * Marks the runtime as shutting down and prevents any further timer or mailbox admission.
     *
     * `mailboxCause` is only used on abort/failure paths where mailbox closure should retain the
     * triggering exception. Cooperative stop closes the mailbox without a cause.
     */
    private fun beginShutdown(mailboxCause: Throwable?) {
        lifecycle.value = ActorLifecycle.SHUTTING_DOWN
        cancelAllActiveTimers()
        if (mailboxCause == null) {
            mailbox.close()
        } else {
            mailbox.close(mailboxCause)
        }
    }

    /**
     * Fails the startup barrier exactly once if the actor never reached RUNNING.
     *
     * Shutdown paths after successful startup still call this helper, but `started` is already
     * completed in that case so the method becomes a no-op.
     */
    private fun failStartupIfNeeded(cause: Throwable) {
        if (!started.isCompleted) {
            ActorLogger.startupFailed(label = label, cause = cause)
        }
        notifyStartFail(cause)
    }

    /**
     * Runs the shared abort path used by startup-stop, external cancellation, and command failure.
     *
     * Unlike cooperative stop, this path does not drain already accepted mailbox work. Pending
     * commands are dropped immediately as undelivered after teardown begins.
     */
    private suspend fun abortActor(
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
        cause: Throwable,
        mailboxCause: Throwable?,
    ) {
        terminalCause.compareAndSet(null, cause)
        beginShutdown(mailboxCause)
        failStartupIfNeeded(cause)
        runPreStop(actor, ctx)
        dropPendingCommands(actor, ctx)
    }

    /**
     * Executes cooperative stop semantics for `shutdown(...)` / `ctx.stopSelf(...)`.
     *
     * The current command is allowed to finish before this method runs. After shutdown begins, the
     * mailbox is closed, teardown starts, and the runtime drains already accepted commands until the
     * optional timeout elapses. Any commands still queued after a timeout are reported as
     * NOT_DELIVERED.
     */
    private suspend fun handleStop(
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
        timeout: Duration?,
    ): Boolean {
        beginShutdown(mailboxCause = null)
        runPreStop(actor, ctx)

        if (!drainMailboxWithin(timeout, actor, ctx)) {
            dropPendingCommands(actor, ctx)
        }
        return false
    }

    /**
     * Drains already accepted mailbox work during cooperative shutdown.
     *
     * A `null` timeout means "drain until empty". A non-null timeout bounds only the draining
     * phase; it does not interrupt the command that was already running before stop began.
     */
    private suspend fun drainMailboxWithin(
        timeout: Duration?,
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
    ): Boolean =
        if (timeout == null) {
            drainMailbox(actor, ctx)
            true
        } else {
            withTimeoutOrNull(timeout) {
                drainMailbox(actor, ctx)
            } != null
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

private object UnsetStopTimeout

private val UNSET_STOP_TIMEOUT: Any = UnsetStopTimeout

/**
 * Maps non-suspending mailbox admission into the public `tell()` result contract.
 *
 * Admission failure is split between "actor is closed" and "mailbox could not accept the command".
 * The latter remains policy-driven so overflow strategies can vary without changing the send call
 * site. Rejected overflow is still a pre-admission failure, not an undelivered command.
 */
private fun ChannelResult<Unit>.toActorSendResult(
    label: String,
    overflowStrategy: OverflowStrategy,
) : Result<Unit> {
    if (isSuccess) {
        return Result.success(Unit)
    }

    val reason =
        if (isClosed) {
            ActorUnavailableReason.ACTOR_CLOSED
        } else {
            when (overflowStrategy) {
                OverflowStrategy.Reject -> ActorUnavailableReason.MAILBOX_FULL
            }
        }

    return Result.failure(
        ActorUnavailable(
            reason = reason,
            label = label,
            cause = exceptionOrNull(),
        ),
    )
}

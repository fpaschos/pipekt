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

private sealed interface SystemEvent {
    data class Stop(
        val timeout: Duration?,
    ) : SystemEvent

    data class WatchNotification(
        val token: Long,
        val termination: ActorTermination,
    ) : SystemEvent
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
 * Concrete actors may keep internal mutable state, but it must only be touched from the actor
 * callbacks that run on the command loop coroutine. See [ActorContext.guardActorAccess] for the
 * explicit runtime guard and usage guidance.
 */
abstract class Actor<Command : Any>(
    internal val capacity: Int = Channel.BUFFERED,
) {
    open suspend fun postStart(ctx: ActorContext<Command>) {}

    abstract suspend fun handle(
        ctx: ActorContext<Command>,
        command: Command,
    )

    open suspend fun preStop(ctx: ActorContext<Command>) {}

    open suspend fun postStop(ctx: ActorContext<Command>) {}

    open suspend fun onCommandFailure(
        ctx: ActorContext<Command>,
        command: Command,
        cause: Throwable,
    ) {}

    open fun onUndeliveredCommand(
        ctx: ActorContext<Command>,
        command: Command,
        reason: ActorUnavailableReason,
    ) {}
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
    private val systemQueue = Channel<SystemEvent>(capacity = Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()
    private val lifecycle = atomic(ActorLifecycle.STARTING)
    private val terminalCause = atomic<Throwable?>(null)
    private val watchState = atomic<WatchState>(WatchState.Active(emptyMap()))
    private val stopRequest = atomic<Any?>(UNSET_STOP_TIMEOUT)
    private val preStopExecuted = atomic(false)

    private lateinit var selfRef: DefaultActorRef<Command>
    internal lateinit var loopJob: Job
        private set

    fun attachRef(ref: DefaultActorRef<Command>) {
        check(!::selfRef.isInitialized) { "Actor runtime for $label already has a ref." }
        selfRef = ref
    }

    fun canRegisterWatches(): Boolean = lifecycle.value != ActorLifecycle.SHUTTING_DOWN && lifecycle.value != ActorLifecycle.SHUTDOWN

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
                        notifyStartFail(cause)
                        mailbox.close()
                        runPreStop(actor, ctx)
                        dropPendingCommands(actor, ctx)
                        keepRunning = false
                    } else {
                        lifecycle.value = ActorLifecycle.RUNNING
                        notifyStart()
                    }

                    while (keepRunning) {
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
                    mailbox.close(ce)
                    notifyStartFail(ce)
                    runPreStop(actor, ctx)
                    dropPendingCommands(actor, ctx)
                    throw ce
                } catch (t: Throwable) {
                    terminalCause.compareAndSet(null, t)
                    lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                    mailbox.close(t)
                    notifyStartFail(t)
                    runPreStop(actor, ctx)
                    dropPendingCommands(actor, ctx)
                } finally {
                    lifecycle.value = ActorLifecycle.SHUTDOWN
                    try {
                        runPostStop(actor, ctx)
                    } finally {
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

        val result = mailbox.trySend(CommandEnvelope(command = command, askReply = askReply))
        return result.toActorResult(
            reason = ActorUnavailableReason.ACTOR_CLOSED,
            label = label,
        )
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
        event: SystemEvent,
    ): Boolean =
        when (event) {
            is SystemEvent.Stop -> {
                lifecycle.value = ActorLifecycle.SHUTTING_DOWN
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
            val envelope = mailbox.tryReceive().getOrNull() ?: return
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

                is WatchState.Terminated -> return
            }
        }
    }

    private fun cancelOwnedScope() {
        if (scope.coroutineContext[OwnedActorScope.Key] != null) {
            scope.coroutineContext.job.cancel()
        }
    }
}

private data object OwnedActorScope :
    AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<OwnedActorScope>
}

private fun <T> ChannelResult<T>.toActorResult(
    reason: ActorUnavailableReason,
    label: String,
): Result<Unit> =
    when {
        isSuccess -> Result.success(Unit)
        isClosed ->
            Result.failure(
                ActorUnavailable(
                    reason = reason,
                    label = label,
                    cause = exceptionOrNull(),
                ),
            )

        else ->
            Result.failure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.MAILBOX_FULL,
                    label = label,
                    cause = exceptionOrNull(),
                ),
            )
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

suspend fun <Command : Any> spawn(
    name: String,
    dispatcher: CoroutineDispatcher? = null,
    factory: () -> Actor<Command>,
): ActorRef<Command> {
    val actor = factory()
    val parentScope = CoroutineScope(currentCoroutineContext())
    val id = nextActorInstanceId.incrementAndGet()
    val label = "$name#$id"
    val runtime = ActorRuntime(name = name, label = label, parentScope = parentScope, dispatcher = dispatcher, actor = actor)
    val ref = DefaultActorRef(name = name, label = label, runtime = runtime)
    runtime.attachRef(ref)
    runtime.start(actor)
    runtime.awaitStarted()
    return ref
}

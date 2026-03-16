package io.github.fpaschos.pipekt.actor

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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

private sealed interface MailboxEvent<out Command : Any> {
    data class UserCommand<Command : Any>(
        val command: Command,
    ) : MailboxEvent<Command>

    data class WatchNotification(
        val token: Long,
        val termination: ActorTermination,
    ) : MailboxEvent<Nothing>
}

private data class StopSignal(
    val timeout: Duration?,
)

private sealed interface WatchState {
    data class Active(
        val listeners: List<WatchRegistration>,
    ) : WatchState

    data class Terminated(
        val termination: ActorTermination,
    ) : WatchState
}

private data class WatchRegistration(
    val watcher: ActorRuntime<*>,
    val token: Long,
)

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
    ) {
        if (command is ReplyRequest<*>) {
            command.failRequest(ActorCommandFailed(ctx.label, cause))
        }
    }

    open fun onUndeliveredCommand(
        ctx: ActorContext<Command>,
        command: Command,
        reason: ActorUnavailableReason,
    ) {
        if (command is ReplyRequest<*>) {
            command.failRequest(
                ActorUnavailable(
                    reason = reason,
                    label = ctx.label,
                ),
            )
        }
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
    private val mailbox = Channel<MailboxEvent<Command>>(actor.capacity)
    private val stopSignal = Channel<StopSignal>(capacity = 1)
    private val started = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()
    private val lifecycle = atomic(ActorLifecycle.STARTING)
    private val terminalCause = atomic<Throwable?>(null)
    private val watchState = atomic<WatchState>(WatchState.Active(emptyList()))

    private lateinit var selfRef: DefaultActorRef<Command>
    internal lateinit var loopJob: Job
        private set

    fun attachRef(ref: DefaultActorRef<Command>) {
        check(!::selfRef.isInitialized) { "Actor runtime for $label already has a ref." }
        selfRef = ref
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(actor: Actor<Command>) {
        check(!::loopJob.isInitialized) { "Actor runtime for $label already started." }
        val ctx = DefaultActorContext(name = name, label = label, self = selfRef, runtime = this)

        loopJob =
            scope.launch(CoroutineName(label)) {
                var keepRunning = true

                try {
                    actor.postStart(ctx)

                    val startupStop = stopSignal.tryReceive().getOrNull()
                    if (startupStop != null) {
                        lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                        notifyStartFail(CancellationException("$label was stopped during startup"))
                        mailbox.close()
                        actor.preStop(ctx)
                        dropPendingEvents(actor, ctx)
                        keepRunning = false
                    } else {
                        lifecycle.value = ActorLifecycle.RUNNING
                        notifyStart()
                    }

                    while (keepRunning) {
                        select {
                            stopSignal.onReceive { request ->
                                lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                                mailbox.close()
                                actor.preStop(ctx)

                                val drained =
                                    if (request.timeout == null) {
                                        drainMailbox(actor, ctx)
                                        true
                                    } else {
                                        withTimeoutOrNull(request.timeout) {
                                            drainMailbox(actor, ctx)
                                            true
                                        } == true
                                    }

                                if (!drained) {
                                    dropPendingEvents(actor, ctx)
                                }
                                keepRunning = false
                            }

                            mailbox.onReceiveCatching { result ->
                                val event =
                                    result.getOrElse {
                                        lifecycle.value = ActorLifecycle.SHUTTING_DOWN
                                        keepRunning = false
                                        return@onReceiveCatching
                                    }
                                handleEvent(actor, ctx, event)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    terminalCause.value = terminalCause.value ?: t
                    notifyStartFail(t)
                } finally {
                    lifecycle.value = ActorLifecycle.SHUTDOWN
                    try {
                        actor.postStop(ctx)
                    } finally {
                        publishTermination()
                        notifyTermination()
                        cancelOwnedScope()
                    }
                }
            }
    }

    fun send(command: Command): Result<Unit> {
        if (lifecycle.value != ActorLifecycle.RUNNING) {
            return Result.failure(
                ActorUnavailable(
                    reason = ActorUnavailableReason.ACTOR_CLOSED,
                    label = label,
                ),
            )
        }

        val result = mailbox.trySend(MailboxEvent.UserCommand(command))
        return result.toActorResult(
            reason = ActorUnavailableReason.ACTOR_CLOSED,
            label = label,
        )
    }

    suspend fun shutdown(timeout: Duration?) {
        if (lifecycle.value == ActorLifecycle.SHUTDOWN) {
            terminated.await()
            cancelOwnedScope()
            return
        }

        stopSignal.trySend(StopSignal(timeout = timeout))
        terminated.await()
        cancelOwnedScope()
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
                    val updated = WatchState.Active(state.listeners + WatchRegistration(watcher, token))
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
        mailbox.trySend(MailboxEvent.WatchNotification(token, termination))
    }

    private suspend fun handleEvent(
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
        event: MailboxEvent<Command>,
    ) {
        when (event) {
            is MailboxEvent.UserCommand -> handleCommand(actor, ctx, event.command)
            is MailboxEvent.WatchNotification -> {
                val mapped = ctx.dispatchWatchNotification(event.token, event.termination) ?: return
                handleCommand(actor, ctx, mapped)
            }
        }
    }

    private suspend fun handleCommand(
        actor: Actor<Command>,
        ctx: ActorContext<Command>,
        command: Command,
    ) {
        try {
            actor.handle(ctx, command)
        } catch (t: Throwable) {
            terminalCause.value = t
            actor.onCommandFailure(ctx, command, t)
            mailbox.close(t)
            dropPendingEvents(actor, ctx)
            throw t
        }
    }

    private suspend fun drainMailbox(
        actor: Actor<Command>,
        ctx: DefaultActorContext<Command>,
    ) {
        while (true) {
            val event = mailbox.receiveCatching().getOrNull() ?: return
            handleEvent(actor, ctx, event)
        }
    }

    private fun dropPendingEvents(
        actor: Actor<Command>,
        ctx: ActorContext<Command>,
    ) {
        while (true) {
            when (val event = mailbox.tryReceive().getOrNull() ?: return) {
                is MailboxEvent.UserCommand -> {
                    actor.onUndeliveredCommand(ctx, event.command, ActorUnavailableReason.NOT_DELIVERED)
                }

                is MailboxEvent.WatchNotification -> Unit
            }
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
                        state.listeners.forEach { listener ->
                            listener.watcher.enqueueWatchNotification(listener.token, termination)
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

private fun ReplyRequest<*>.failRequest(cause: Throwable) {
    @Suppress("UNCHECKED_CAST")
    (replyTo as ReplyChannel<Any?>).failure(cause)
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

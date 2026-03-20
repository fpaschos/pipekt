package io.github.fpaschos.pipekt.actor.runtime

import io.github.fpaschos.pipekt.actor.ActorTermination
import io.github.fpaschos.pipekt.actor.AskReplyHandle
import io.github.fpaschos.pipekt.actor.TimerKey
import kotlinx.coroutines.Job
import kotlin.time.Duration

internal enum class ActorLifecycle {
    STARTING,
    RUNNING,
    SHUTTING_DOWN,
    SHUTDOWN,
}

internal data class CommandEnvelope<Command : Any>(
    val command: Command,
    val askReply: AskReplyHandle? = null,
)

internal sealed interface SystemEvent<out Command : Any> {
    data class Stop(
        val timeout: Duration?,
    ) : SystemEvent<Nothing>

    data class WatchNotification(
        val watched: ActorRuntime<*>,
        val termination: ActorTermination,
    ) : SystemEvent<Nothing>

    data class TimerFired<Command : Any>(
        val key: TimerKey,
        val generation: Long,
        val command: Command,
    ) : SystemEvent<Command>
}

internal sealed interface WatchState {
    data class Active(
        val listeners: Set<ActorRuntime<*>>,
    ) : WatchState

    data class Terminated(
        val termination: ActorTermination,
    ) : WatchState
}

internal data class ActiveTimer(
    val generation: Long,
    val mode: TimerMode,
    val job: Job,
)

internal enum class TimerMode {
    Once,
    Repeated,
}

package io.github.fpaschos.pipekt.fixtures

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.ActorRef
import io.github.fpaschos.pipekt.actor.ActorTimers
import io.github.fpaschos.pipekt.actor.TimerKey
import kotlinx.coroutines.delay

sealed interface TimerCommand {
    data class StartOnce(
        val key: TimerKey,
        val delay: kotlin.time.Duration,
        val value: String,
        val replyTo: ActorRef<Unit>,
    ) : TimerCommand

    data class StartRepeated(
        val key: TimerKey,
        val interval: kotlin.time.Duration,
        val value: String,
        val replyTo: ActorRef<Unit>,
    ) : TimerCommand

    data class ReplaceQueuedOnce(
        val key: TimerKey,
        val firstDelay: kotlin.time.Duration,
        val replacementDelay: kotlin.time.Duration,
        val pauseBeforeReplace: kotlin.time.Duration,
        val firstValue: String,
        val replacementValue: String,
        val replyTo: ActorRef<Unit>,
    ) : TimerCommand

    data class CancelQueuedOnce(
        val key: TimerKey,
        val delay: kotlin.time.Duration,
        val pauseBeforeCancel: kotlin.time.Duration,
        val value: String,
        val replyTo: ActorRef<Unit>,
    ) : TimerCommand

    data class Snapshot(
        val replyTo: ActorRef<List<String>>,
    ) : TimerCommand

    data class LeakTimers(
        val replyTo: ActorRef<ActorTimers<TimerCommand>>,
    ) : TimerCommand

    data class Record(
        val value: String,
    ) : TimerCommand
}

class TimerActor(
    private val externalEvents: MutableList<String> = mutableListOf(),
) : Actor<TimerCommand>() {
    private val recorded = mutableListOf<String>()

    override suspend fun handle(
        ctx: ActorContext<TimerCommand>,
        command: TimerCommand,
    ) {
        when (command) {
            is TimerCommand.StartOnce -> {
                ctx.timers.once(command.key, command.delay, TimerCommand.Record(command.value))
                command.replyTo.tell(Unit)
            }

            is TimerCommand.StartRepeated -> {
                ctx.timers.repeated(command.key, command.interval, TimerCommand.Record(command.value))
                command.replyTo.tell(Unit)
            }

            is TimerCommand.ReplaceQueuedOnce -> {
                ctx.timers.once(command.key, command.firstDelay, TimerCommand.Record(command.firstValue))
                delay(command.pauseBeforeReplace)
                ctx.timers.once(command.key, command.replacementDelay, TimerCommand.Record(command.replacementValue))
                command.replyTo.tell(Unit)
            }

            is TimerCommand.CancelQueuedOnce -> {
                ctx.timers.once(command.key, command.delay, TimerCommand.Record(command.value))
                delay(command.pauseBeforeCancel)
                ctx.timers.cancel(command.key)
                command.replyTo.tell(Unit)
            }

            is TimerCommand.Snapshot -> {
                command.replyTo.tell(recorded.toList())
            }

            is TimerCommand.LeakTimers -> {
                command.replyTo.tell(ctx.timers)
            }

            is TimerCommand.Record -> {
                recorded += command.value
                externalEvents += command.value
            }
        }
    }
}

package io.github.fpaschos.pipekt.runtime.new.actors

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.TimerKey
import io.github.fpaschos.pipekt.runtime.new.RuntimeConfig
import io.github.fpaschos.pipekt.store.DurableStore
import kotlin.time.Clock

/**
 * Periodically reclaims expired work leases for the store.
 *
 * This actor is timer-driven only. It does not expose a user-command protocol, so `capacity = 0`
 * keeps the user mailbox disabled while scheduled timer events continue to arrive through the
 * actor runtime's internal system queue.
 */
internal class LeaseReclaimer(
    private val store: DurableStore,
    private val config: RuntimeConfig,
) : Actor<LeaseReclaimer.Command>(capacity = 0) {
    sealed interface Command {
        /** Internal tick command */
        data object Tick : Command
    }

    private val timer = TimerKey("lease-timer")

    override suspend fun postStart(ctx: ActorContext<Command>) {
        ctx.timers.once(timer, config.watchdogInterval, Command.Tick)
    }

    override suspend fun handle(
        ctx: ActorContext<Command>,
        command: Command,
    ) {
        when (command) {
            Command.Tick -> {
                store.reclaimExpiredLeases(
                    now = Clock.System.now(),
                    limit = config.workerClaimLimit,
                )
                ctx.timers.once(timer, config.watchdogInterval, Command.Tick)
            }
        }
    }
}

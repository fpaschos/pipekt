package io.github.fpaschos.pipekt.actor

import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

internal object ActorLogger {
    // Single logger instance for all actor-related logging.
    private val log = Logger.of("pipekt.actor")

    fun started(label: String) {
        log.debug { "Actor $label started." }
    }

    fun startupFailed(
        label: String,
        cause: Throwable,
    ) {
        log.error(cause) { "Actor $label failed during startup." }
    }

    fun stopRequested(
        label: String,
        timeout: Duration?,
    ) {
        log.debug { "Actor $label stop requested (timeout=$timeout)." }
    }

    fun stopped(
        label: String,
        cause: Throwable?,
    ) {
        when (cause) {
            null -> log.debug { "Actor $label stopped." }
            is CancellationException -> log.debug { "Actor $label stopped due to cancellation." }
            else -> log.error(cause) { "Actor $label stopped with failure." }
        }
    }

    fun commandFailed(
        label: String,
        cause: Throwable,
    ) {
        log.error(cause) { "Actor $label command failed." }
    }
}

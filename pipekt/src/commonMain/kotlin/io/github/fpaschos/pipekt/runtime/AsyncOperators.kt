package io.github.fpaschos.pipekt.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel

/** Utility function to ensure completion of  reply with result or exception. */
suspend inline fun <T> completeSafe(
    reply: CompletableDeferred<T>,
    crossinline block: suspend () -> T,
) {
    runCatching { block() }
        .onSuccess { reply.complete(it) }
        .onFailure { reply.completeExceptionally(it) }
}

/**
 * Sends a request command carrying a reply slot and awaits its completion.
 *
 * Use this for the common actor request/reply pattern:
 * create deferred -> send command(deferred) -> await deferred.
 */
suspend inline fun <C, R> request(
    mailbox: SendChannel<C>,
    build: (CompletableDeferred<R>) -> C,
): R {
    val reply = CompletableDeferred<R>()
    mailbox.send(build(reply))
    return reply.await()
}

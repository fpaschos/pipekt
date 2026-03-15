package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred

/**
 * Small request/reply transport used by actor commands.
 *
 * Command protocols see only this abstraction; the deferred used by [ask] stays internal to the
 * actor package.
 */
interface ReplyChannel<in Reply> {
    fun success(value: Reply): Boolean

    fun failure(cause: Throwable): Boolean
}

/**
 * Marker for commands that carry a shared reply transport.
 */
interface ReplyRequest<Reply> {
    val replyTo: ReplyChannel<Reply>
}

/**
 * Shared base class for request commands so each message does not need to reimplement failure
 * plumbing.
 */
abstract class Request<Reply>(
    final override val replyTo: ReplyChannel<Reply>,
) : ReplyRequest<Reply> {
    fun success(value: Reply): Boolean = replyTo.success(value)

    fun failure(cause: Throwable): Boolean = replyTo.failure(cause)
}

internal class DeferredReplyChannel<Reply> : ReplyChannel<Reply> {
    private val deferred = CompletableDeferred<Reply>()

    override fun success(value: Reply): Boolean = deferred.complete(value)

    override fun failure(cause: Throwable): Boolean = deferred.completeExceptionally(cause)

    suspend fun await(): Reply = deferred.await()

    fun cancel() {
        deferred.cancel()
    }
}

internal fun <Reply> deferredReplyChannel(): DeferredReplyChannel<Reply> = DeferredReplyChannel()

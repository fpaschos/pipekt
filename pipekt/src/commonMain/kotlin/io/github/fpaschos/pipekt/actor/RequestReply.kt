package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred

/**
 * Narrow typed sink for request/reply protocols.
 */
interface ReplyRef<in Reply> {
    fun tell(reply: Reply): Result<Unit>

    fun fail(cause: Throwable): Result<Unit> = Result.failure(
        UnsupportedOperationException("This reply ref does not support failure delivery."),
    )
}

internal interface AskReplyHandle {
    fun completeFailure(cause: Throwable): Boolean

    fun cancel()
}

internal class DeferredReplyRef<Reply> :
    ReplyRef<Reply>,
    AskReplyHandle {
    private val deferred = CompletableDeferred<Reply>()

    override fun tell(reply: Reply): Result<Unit> =
        if (deferred.complete(reply)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Reply already completed."))
        }

    override fun fail(cause: Throwable): Result<Unit> =
        if (deferred.completeExceptionally(cause)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Reply already completed."))
        }

    override fun completeFailure(cause: Throwable): Boolean = deferred.completeExceptionally(cause)

    suspend fun await(): Reply = deferred.await()

    override fun cancel() {
        deferred.cancel()
    }
}

internal fun <Reply> deferredReplyRef(): DeferredReplyRef<Reply> = DeferredReplyRef()

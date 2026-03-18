package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred

/**
 * Narrow typed sink for request/reply protocols.
 *
 * Actor request/reply commands should depend on [ReplyRef] instead of a full [ActorRef] when they
 * only need to emit a response. This keeps request/reply protocols narrower than actor lifecycle
 * control.
 */
interface ReplyRef<in Reply> {
    /**
     * Attempts to deliver [reply] to the receiver.
     *
     * Reply refs created by [ask] are one-shot: the first successful reply wins and later replies
     * fail.
     */
    fun tell(reply: Reply): Result<Unit>

    /**
     * Attempts to complete the reply with a failure.
     *
     * The default implementation rejects failure delivery so that reply-capable APIs opt into
     * failure semantics explicitly.
     */
    fun fail(cause: Throwable): Result<Unit> =
        Result.failure(
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

    // Ask replies are one-shot: only the first successful reply or failure completes the request.
    override fun completeFailure(cause: Throwable): Boolean = deferred.completeExceptionally(cause)

    suspend fun await(): Reply = deferred.await()

    override fun cancel() {
        deferred.cancel()
    }
}

internal fun <Reply> deferredReplyRef(): DeferredReplyRef<Reply> = DeferredReplyRef()

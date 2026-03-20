package io.github.fpaschos.pipekt.actor

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration

internal interface AskReplyHandle {
    fun completeFailure(cause: Throwable): Boolean

    fun cancel()
}

private val nextAskReplyId = atomic(0L)

internal class DeferredReplyActorRef<Reply : Any> :
    ActorRef<Reply>,
    AskReplyHandle {
    private val deferred = CompletableDeferred<Reply>()
    private val id = nextAskReplyId.incrementAndGet()

    override val name: String = "ask-reply"
    override val label: String = "$name#$id"

    override fun tell(command: Reply): Result<Unit> =
        if (deferred.complete(command)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Reply already completed."))
        }

    override suspend fun shutdown(timeout: Duration?): Unit =
        throw UnsupportedOperationException("Temporary ask reply actor refs do not support shutdown().")

    // Ask replies are one-shot: only the first successful reply or failure completes the request.
    override fun completeFailure(cause: Throwable): Boolean = deferred.completeExceptionally(cause)

    suspend fun await(): Reply = deferred.await()

    override fun cancel() {
        deferred.cancel()
    }
}

internal fun <Reply : Any> deferredReplyActorRef(): DeferredReplyActorRef<Reply> = DeferredReplyActorRef()

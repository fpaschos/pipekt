package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

sealed interface TestCommand {
    data class Ping(
        val value: String,
        val reply: CompletableDeferred<String>,
    ) : TestCommand, ReplyingCommand {
        override fun completeExceptionally(cause: Throwable) {
            reply.completeExceptionally(cause)
        }
    }

    data class Fail(
        val reply: CompletableDeferred<String>,
    ) : TestCommand, ReplyingCommand {
        override fun completeExceptionally(cause: Throwable) {
            reply.completeExceptionally(cause)
        }
    }
}

class MinimalActor(
    scope: CoroutineScope,
    name: String,
) : Actor<TestCommand>(scope, name, Channel.BUFFERED) {
    override suspend fun handle(command: TestCommand) {
        when (command) {
            is TestCommand.Ping -> command.reply.complete("echo: ${command.value}")
            is TestCommand.Fail -> error("boom")
        }
    }

    suspend fun ping(value: String): String = request { reply -> TestCommand.Ping(value, reply) }

    suspend fun fail(): String = request { reply -> TestCommand.Fail(reply) }

    suspend fun shutdownInternal(timeout: Duration?) {
        shutdown(timeout = timeout)
    }

    companion object {
        suspend fun spawn(scope: CoroutineScope): MinimalActorRef {
            val actor = MinimalActor(scope, "test-echo-actor")
            actor.awaitStarted()
            return MinimalActorRef(actor)
        }
    }
}

class MinimalActorRef(
    private val actor: MinimalActor,
) : ActorRef() {
    suspend fun ping(value: String): String = actor.ping(value)

    suspend fun fail(): String = actor.fail()

    override suspend fun shutdownActor(timeout: Duration?) {
        actor.shutdownInternal(timeout)
    }
}

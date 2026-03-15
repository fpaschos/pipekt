package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface TestCommand {
    data class Record(
        val value: String,
    ) : TestCommand

    data class Ping(
        val value: String,
        val reply: CompletableDeferred<String>,
    ) : TestCommand,
        ReplyingCommand {
        override fun completeExceptionally(cause: Throwable) {
            reply.completeExceptionally(cause)
        }
    }

    data class Snapshot(
        val reply: CompletableDeferred<List<String>>,
    ) : TestCommand,
        ReplyingCommand {
        override fun completeExceptionally(cause: Throwable) {
            reply.completeExceptionally(cause)
        }
    }

    data class Fail(
        val reply: CompletableDeferred<String>,
    ) : TestCommand,
        ReplyingCommand {
        override fun completeExceptionally(cause: Throwable) {
            reply.completeExceptionally(cause)
        }
    }

    data class SlowPing(
        val value: String,
        val gate: CompletableDeferred<Unit>,
        val reply: CompletableDeferred<String>,
    ) : TestCommand,
        ReplyingCommand {
        override fun completeExceptionally(cause: Throwable) {
            reply.completeExceptionally(cause)
        }
    }

    data class Block(
        val gate: CompletableDeferred<Unit>,
    ) : TestCommand
}

class MinimalActor(
    scope: CoroutineScope,
    name: String,
    private val events: MutableList<String> = mutableListOf(),
    private val startupGate: CompletableDeferred<Unit>? = null,
) : Actor<TestCommand>(scope, name, Channel.BUFFERED) {
    private val recorded = mutableListOf<String>()
    private val stateMutex = Mutex()

    override suspend fun postStart() {
        events.add("postStart:begin")
        startupGate?.await()
        events.add("postStart:end")
    }

    override suspend fun handle(command: TestCommand) {
        when (command) {
            is TestCommand.Record -> {
                stateMutex.withLock {
                    recorded += command.value
                }
                events.add("handle:record:${command.value}")
            }

            is TestCommand.Ping -> {
                events.add("handle:ping:${command.value}")
                command.reply.complete("echo: ${command.value}")
            }

            is TestCommand.Snapshot -> {
                events.add("handle:snapshot")
                command.reply.complete(stateMutex.withLock { recorded.toList() })
            }

            is TestCommand.Fail -> {
                events.add("handle:fail")
                error("boom")
            }

            is TestCommand.SlowPing -> {
                events.add("handle:slow-ping:${command.value}:begin")
                command.gate.await()
                events.add("handle:slow-ping:${command.value}:end")
                command.reply.complete("echo: ${command.value}")
            }

            is TestCommand.Block -> {
                events.add("handle:block:begin")
                command.gate.await()
                events.add("handle:block:end")
            }
        }
    }

    override suspend fun preStop() {
        events.add("preStop")
    }

    override suspend fun postStop() {
        events.add("postStop")
    }
}

class RecordingActor(
    scope: CoroutineScope,
    name: String,
    private val undelivered: MutableList<String>,
) : Actor<TestCommand>(scope, name, Channel.BUFFERED) {
    override suspend fun handle(command: TestCommand) {
        when (command) {
            is TestCommand.Record -> Unit
            is TestCommand.Ping -> command.reply.complete("echo: ${command.value}")
            is TestCommand.Snapshot -> command.reply.complete(emptyList())
            is TestCommand.Fail -> error("boom")
            is TestCommand.SlowPing -> command.reply.complete("echo: ${command.value}")
            is TestCommand.Block -> Unit
        }
    }

    override fun onUndeliveredCommand(
        command: TestCommand,
        reason: ActorUnavailableReason,
    ) {
        val label =
            when (command) {
                is TestCommand.Record -> "Record"
                is TestCommand.Ping -> "Ping"
                is TestCommand.Snapshot -> "Snapshot"
                is TestCommand.Fail -> "Fail"
                is TestCommand.SlowPing -> "SlowPing"
                is TestCommand.Block -> "Block"
            }
        undelivered += "$label:${reason.name}"
        super.onUndeliveredCommand(command, reason)
    }
}

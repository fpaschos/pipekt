package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

sealed interface TestCommand {
    data class Record(
        val value: String,
    ) : TestCommand

    data class Ping(
        val value: String,
        private val channel: ReplyChannel<String>,
    ) : Request<String>(channel),
        TestCommand

    data class Snapshot(
        private val channel: ReplyChannel<List<String>>,
    ) : Request<List<String>>(channel),
        TestCommand

    data class Fail(
        private val channel: ReplyChannel<String>,
    ) : Request<String>(channel),
        TestCommand

    data class SlowPing(
        val value: String,
        val gate: CompletableDeferred<Unit>,
        private val channel: ReplyChannel<String>,
    ) : Request<String>(channel),
        TestCommand

    data class Block(
        val gate: CompletableDeferred<Unit>,
    ) : TestCommand

    data class LoopName(
        private val channel: ReplyChannel<String?>,
    ) : Request<String?>(channel),
        TestCommand
}

class MinimalActor(
    scope: CoroutineScope,
    name: String,
    private val events: MutableList<String> = mutableListOf(),
    private val startupGate: CompletableDeferred<Unit>? = null,
    capacity: Int = Channel.BUFFERED,
) : Actor<TestCommand>(scope, name, capacity) {
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
                command.success("echo: ${command.value}")
            }

            is TestCommand.Snapshot -> {
                events.add("handle:snapshot")
                command.success(stateMutex.withLock { recorded.toList() })
            }

            is TestCommand.Fail -> {
                events.add("handle:fail")
                error("boom")
            }

            is TestCommand.SlowPing -> {
                events.add("handle:slow-ping:${command.value}:begin")
                command.gate.await()
                events.add("handle:slow-ping:${command.value}:end")
                command.success("echo: ${command.value}")
            }

            is TestCommand.Block -> {
                events.add("handle:block:begin")
                command.gate.await()
                events.add("handle:block:end")
            }

            is TestCommand.LoopName -> command.success(coroutineContext[CoroutineName]?.name)
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
            is TestCommand.Ping -> command.success("echo: ${command.value}")
            is TestCommand.Snapshot -> command.success(emptyList())
            is TestCommand.Fail -> error("boom")
            is TestCommand.SlowPing -> command.success("echo: ${command.value}")
            is TestCommand.Block -> Unit
            is TestCommand.LoopName -> command.success(null)
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
                is TestCommand.LoopName -> "LoopName"
            }
        undelivered += "$label:${reason.name}"
        super.onUndeliveredCommand(command, reason)
    }
}

class FailingStartActor(
    scope: CoroutineScope,
    name: String,
    private val startupFailure: Throwable,
) : Actor<TestCommand>(scope, name, Channel.BUFFERED) {
    override suspend fun postStart(): Unit = throw startupFailure

    override suspend fun handle(command: TestCommand) = Unit
}

sealed interface ChildCommand {
    data object Fail : ChildCommand

    data object Capture : ChildCommand
}

sealed interface ParentCommand {
    data class SnapshotEvents(
        private val channel: ReplyChannel<List<String>>,
    ) : Request<List<String>>(channel),
        ParentCommand

    data class StopChild(
        private val channel: ReplyChannel<Unit>,
    ) : Request<Unit>(channel),
        ParentCommand

    data class FailChild(
        private val channel: ReplyChannel<Unit>,
    ) : Request<Unit>(channel),
        ParentCommand

    data class ChildObserved(
        val termination: ChildTermination,
    ) : ParentCommand

    data class ChildCaptured(
        val value: String,
    ) : ParentCommand
}

class ChildActor(
    scope: CoroutineScope,
    name: String,
    private val events: MutableList<String>,
) : Actor<ChildCommand>(scope, name, Channel.BUFFERED) {
    override suspend fun handle(command: ChildCommand) {
        when (command) {
            ChildCommand.Fail -> error("child-boom")
            ChildCommand.Capture -> events += "child:capture"
        }
    }

    override suspend fun postStop() {
        events += "child:postStop"
    }
}

class ParentActor(
    scope: CoroutineScope,
    name: String,
    private val events: MutableList<String>,
) : Actor<ParentCommand>(scope, name, Channel.BUFFERED) {
    private lateinit var child: ActorRef<ChildCommand>

    override suspend fun postStart() {
        child =
            spawnOwnedChild(
                actorName = "owned-child",
                onTerminated = { ParentCommand.ChildObserved(it) },
            ) { childScope, childName ->
                ChildActor(childScope, childName, events)
            }
    }

    override suspend fun handle(command: ParentCommand) {
        when (command) {
            is ParentCommand.SnapshotEvents -> command.success(events.toList())
            is ParentCommand.StopChild -> {
                child.shutdown()
                command.success(Unit)
            }

            is ParentCommand.FailChild -> {
                child.tell(ChildCommand.Fail).getOrThrow()
                command.success(Unit)
            }

            is ParentCommand.ChildObserved -> {
                val causeName = command.termination.cause?.message ?: "normal"
                events += "parent:child-terminated:${command.termination.childLabel}:$causeName"
            }

            is ParentCommand.ChildCaptured -> events += "parent:captured:${command.value}"
        }
    }
}

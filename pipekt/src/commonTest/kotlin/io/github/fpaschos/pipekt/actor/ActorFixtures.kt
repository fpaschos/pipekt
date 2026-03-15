package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel

class EventRecorder {
    private val events = mutableListOf<String>()

    fun record(event: String) {
        events += event
    }

    fun snapshot(): List<String> = events.toList()
}

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
    ctx: ActorContext<TestCommand>,
    private val events: EventRecorder = EventRecorder(),
    private val startupGate: CompletableDeferred<Unit>? = null,
    capacity: Int = Channel.BUFFERED,
) : Actor<TestCommand>(ctx, capacity) {
    private val recorded = mutableListOf<String>()

    override suspend fun postStart() {
        events.record("postStart:begin")
        startupGate?.await()
        events.record("postStart:end")
    }

    override suspend fun handle(command: TestCommand) {
        when (command) {
            is TestCommand.Record -> {
                recorded += command.value
                events.record("handle:record:${command.value}")
            }

            is TestCommand.Ping -> {
                events.record("handle:ping:${command.value}")
                command.success("echo: ${command.value}")
            }

            is TestCommand.Snapshot -> {
                events.record("handle:snapshot")
                command.success(recorded.toList())
            }

            is TestCommand.Fail -> {
                events.record("handle:fail")
                error("boom")
            }

            is TestCommand.SlowPing -> {
                events.record("handle:slow-ping:${command.value}:begin")
                command.gate.await()
                events.record("handle:slow-ping:${command.value}:end")
                command.success("echo: ${command.value}")
            }

            is TestCommand.Block -> {
                events.record("handle:block:begin")
                command.gate.await()
                events.record("handle:block:end")
            }

            is TestCommand.LoopName -> {
                command.success(currentCoroutineContext()[CoroutineName]?.name)
            }
        }
    }

    override suspend fun preStop() {
        events.record("preStop")
    }

    override suspend fun postStop() {
        events.record("postStop")
    }
}

class RecordingActor(
    ctx: ActorContext<TestCommand>,
    private val undelivered: MutableList<String>,
) : Actor<TestCommand>(ctx, Channel.BUFFERED) {
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
    ctx: ActorContext<TestCommand>,
    private val startupFailure: Throwable,
) : Actor<TestCommand>(ctx, Channel.BUFFERED) {
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
    ctx: ActorContext<ChildCommand>,
    private val events: EventRecorder,
) : Actor<ChildCommand>(ctx, Channel.BUFFERED) {
    override suspend fun handle(command: ChildCommand) {
        when (command) {
            ChildCommand.Fail -> error("child-boom")
            ChildCommand.Capture -> events.record("child:capture")
        }
    }

    override suspend fun postStop() {
        events.record("child:postStop")
    }
}

class ParentActor(
    ctx: ActorContext<ParentCommand>,
    private val events: EventRecorder,
) : Actor<ParentCommand>(ctx, Channel.BUFFERED) {
    private lateinit var child: ActorRef<ChildCommand>

    override suspend fun postStart() {
        child =
            ctx.spawn(name = "owned-child") { childCtx ->
                ChildActor(childCtx, events)
            }
        ctx.watch(child) { ParentCommand.ChildObserved(it) }
    }

    override suspend fun handle(command: ParentCommand) {
        when (command) {
            is ParentCommand.SnapshotEvents -> {
                command.success(events.snapshot())
            }

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
                events.record("parent:child-terminated:${command.termination.childLabel}:$causeName")
            }

            is ParentCommand.ChildCaptured -> {
                events.record("parent:captured:${command.value}")
            }
        }
    }
}

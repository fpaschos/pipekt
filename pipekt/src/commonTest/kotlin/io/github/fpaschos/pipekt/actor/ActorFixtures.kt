package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        val replyTo: ReplyRef<String>,
    ) : TestCommand

    data class Snapshot(
        val replyTo: ReplyRef<List<String>>,
    ) : TestCommand

    data class Fail(
        val replyTo: ReplyRef<String>,
    ) : TestCommand

    data class SlowPing(
        val value: String,
        val gate: CompletableDeferred<Unit>,
        val replyTo: ReplyRef<String>,
    ) : TestCommand

    data class Block(
        val gate: CompletableDeferred<Unit>,
    ) : TestCommand

    data class LoopName(
        val replyTo: ReplyRef<String?>,
    ) : TestCommand

    data class DoubleReply(
        val replyTo: ReplyRef<String>,
    ) : TestCommand

    data object StopSelf : TestCommand

    data class SelfShutdown(
        val replyTo: ReplyRef<String>,
    ) : TestCommand

    data class CancelCurrent(
        val replyTo: ReplyRef<String>,
    ) : TestCommand
}

class MinimalActor(
    private val events: EventRecorder = EventRecorder(),
    private val startupGate: CompletableDeferred<Unit>? = null,
    capacity: Int = Channel.BUFFERED,
) : Actor<TestCommand>(capacity) {
    private val recorded = mutableListOf<String>()

    override suspend fun postStart(ctx: ActorContext<TestCommand>) {
        events.record("postStart:begin")
        startupGate?.await()
        events.record("postStart:end")
    }

    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) {
        when (command) {
            is TestCommand.Record -> {
                recorded += command.value
                events.record("handle:record:${command.value}")
            }

            is TestCommand.Ping -> {
                events.record("handle:ping:${command.value}")
                command.replyTo.tell("echo: ${command.value}")
            }

            is TestCommand.Snapshot -> {
                events.record("handle:snapshot")
                command.replyTo.tell(recorded.toList())
            }

            is TestCommand.Fail -> {
                events.record("handle:fail")
                error("boom")
            }

            is TestCommand.SlowPing -> {
                events.record("handle:slow-ping:${command.value}:begin")
                command.gate.await()
                events.record("handle:slow-ping:${command.value}:end")
                command.replyTo.tell("echo: ${command.value}")
            }

            is TestCommand.Block -> {
                events.record("handle:block:begin")
                command.gate.await()
                events.record("handle:block:end")
            }

            is TestCommand.LoopName -> {
                command.replyTo.tell(currentCoroutineContext()[CoroutineName]?.name)
            }

            is TestCommand.DoubleReply -> {
                command.replyTo.tell("first")
                command.replyTo.tell("second")
            }

            TestCommand.StopSelf -> {
                ctx.stopSelf()
            }

            is TestCommand.SelfShutdown -> {
                runCatching { ctx.self.shutdown() }
                    .exceptionOrNull()
                    ?.let { command.replyTo.tell(it.message ?: "missing") }
            }

            is TestCommand.CancelCurrent -> {
                delay(5.seconds)
                command.replyTo.tell("unreachable")
            }
        }
    }

    override suspend fun preStop(ctx: ActorContext<TestCommand>) {
        events.record("preStop")
    }

    override suspend fun postStop(ctx: ActorContext<TestCommand>) {
        events.record("postStop")
    }
}

class RecordingActor(
    private val undelivered: MutableList<String>,
) : Actor<TestCommand>(Channel.BUFFERED) {
    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) {
        when (command) {
            is TestCommand.Record -> {
                Unit
            }

            is TestCommand.Ping -> {
                command.replyTo.tell("echo: ${command.value}")
            }

            is TestCommand.Snapshot -> {
                command.replyTo.tell(emptyList())
            }

            is TestCommand.Fail -> {
                error("boom")
            }

            is TestCommand.SlowPing -> {
                command.replyTo.tell("echo: ${command.value}")
            }

            is TestCommand.Block -> {
                Unit
            }

            is TestCommand.LoopName -> {
                command.replyTo.tell(null)
            }

            is TestCommand.DoubleReply -> {
                command.replyTo.tell("first")
                command.replyTo.tell("second")
            }

            TestCommand.StopSelf -> {
                ctx.stopSelf()
            }

            is TestCommand.SelfShutdown -> {
                command.replyTo.tell("unsupported")
            }

            is TestCommand.CancelCurrent -> {
                error("unsupported")
            }
        }
    }

    override fun onUndeliveredCommand(
        ctx: ActorContext<TestCommand>,
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
                is TestCommand.DoubleReply -> "DoubleReply"
                TestCommand.StopSelf -> "StopSelf"
                is TestCommand.SelfShutdown -> "SelfShutdown"
                is TestCommand.CancelCurrent -> "CancelCurrent"
            }
        undelivered += "$label:${reason.name}"
        super.onUndeliveredCommand(ctx, command, reason)
    }
}

class FailingStartActor(
    private val startupFailure: Throwable,
) : Actor<TestCommand>(Channel.BUFFERED) {
    override suspend fun postStart(ctx: ActorContext<TestCommand>): Unit = throw startupFailure

    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) = Unit
}

sealed interface ChildCommand {
    data object Fail : ChildCommand

    data object Capture : ChildCommand

    data class ReplyParent(
        val replyTo: ReplyRef<ParentCommand>,
    ) : ChildCommand
}

sealed interface ParentCommand {
    data class SnapshotEvents(
        val replyTo: ReplyRef<List<String>>,
    ) : ParentCommand

    data class StopChild(
        val replyTo: ReplyRef<Unit>,
    ) : ParentCommand

    data class FailChild(
        val replyTo: ReplyRef<Unit>,
    ) : ParentCommand

    data class ChildObserved(
        val termination: ActorTermination,
    ) : ParentCommand

    data class WatchStopped(
        val replyTo: ReplyRef<Unit>,
    ) : ParentCommand

    data class WatchActive(
        val replyTo: ReplyRef<Unit>,
    ) : ParentCommand

    data class TriggerChildReply(
        val replyTo: ReplyRef<Unit>,
    ) : ParentCommand

    data class Block(
        val gate: CompletableDeferred<Unit>,
    ) : ParentCommand

    data object ChildReply : ParentCommand
}

class ChildActor(
    private val events: EventRecorder,
) : Actor<ChildCommand>(Channel.BUFFERED) {
    override suspend fun handle(
        ctx: ActorContext<ChildCommand>,
        command: ChildCommand,
    ) {
        when (command) {
            ChildCommand.Fail -> error("child-boom")
            ChildCommand.Capture -> events.record("child:capture")
            is ChildCommand.ReplyParent -> command.replyTo.tell(ParentCommand.ChildReply)
        }
    }

    override suspend fun postStop(ctx: ActorContext<ChildCommand>) {
        events.record("child:postStop")
    }
}

class ParentActor(
    private val events: EventRecorder,
    private val childRef: CompletableDeferred<ActorRef<ChildCommand>>? = null,
    private val selfRef: CompletableDeferred<ActorRef<ParentCommand>>? = null,
    capacity: Int = Channel.BUFFERED,
) : Actor<ParentCommand>(capacity) {
    private lateinit var child: ActorRef<ChildCommand>

    override suspend fun postStart(ctx: ActorContext<ParentCommand>) {
        selfRef?.complete(ctx.self)
        child = spawn(name = "watched-child") { ChildActor(events) }
        childRef?.complete(child)
        ctx.watch(child) { ParentCommand.ChildObserved(it) }
    }

    override suspend fun handle(
        ctx: ActorContext<ParentCommand>,
        command: ParentCommand,
    ) {
        when (command) {
            is ParentCommand.SnapshotEvents -> {
                command.replyTo.tell(events.snapshot())
            }

            is ParentCommand.StopChild -> {
                child.shutdown()
                command.replyTo.tell(Unit)
            }

            is ParentCommand.FailChild -> {
                child.tell(ChildCommand.Fail).getOrThrow()
                command.replyTo.tell(Unit)
            }

            is ParentCommand.ChildObserved -> {
                val causeName = command.termination.cause?.message ?: "normal"
                events.record("parent:child-terminated:${command.termination.actorLabel}:$causeName")
            }

            is ParentCommand.WatchStopped -> {
                ctx.watch(child) { ParentCommand.ChildObserved(it) }
                command.replyTo.tell(Unit)
            }

            is ParentCommand.WatchActive -> {
                ctx.watch(child) { ParentCommand.ChildObserved(it) }
                command.replyTo.tell(Unit)
            }

            is ParentCommand.TriggerChildReply -> {
                child.tell(ChildCommand.ReplyParent(ctx.self)).getOrThrow()
                command.replyTo.tell(Unit)
            }

            is ParentCommand.Block -> {
                events.record("parent:block:begin")
                command.gate.await()
                events.record("parent:block:end")
            }

            ParentCommand.ChildReply -> {
                events.record("parent:child-reply")
            }
        }
    }

    override suspend fun preStop(ctx: ActorContext<ParentCommand>) {
        if (::child.isInitialized) {
            child.shutdown()
        }
    }
}

class SelfCapturingActor(
    private val selfRef: CompletableDeferred<ActorRef<TestCommand>>,
) : Actor<TestCommand>() {
    override suspend fun postStart(ctx: ActorContext<TestCommand>) {
        selfRef.complete(ctx.self)
    }

    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) = Unit
}

class ForeignWatchingActor(
    private val foreignRef: ActorRef<Any>,
) : Actor<TestCommand>() {
    override suspend fun postStart(ctx: ActorContext<TestCommand>) {
        ctx.watch(foreignRef) { TestCommand.Record(it.actorLabel) }
    }

    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) = Unit
}

class SelfWatchingActor : Actor<TestCommand>() {
    override suspend fun postStart(ctx: ActorContext<TestCommand>) {
        ctx.watch(ctx.self) { TestCommand.Record(it.actorLabel) }
    }

    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) = Unit
}

class WatchDuringShutdownActor(
    private val target: ActorRef<TestCommand>,
    private val failure: CompletableDeferred<Throwable>,
) : Actor<TestCommand>() {
    override suspend fun preStop(ctx: ActorContext<TestCommand>) {
        runCatching {
            ctx.watch(target) { TestCommand.Record(it.actorLabel) }
        }.exceptionOrNull()?.let { failure.complete(it) }
    }

    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) = Unit
}

class CancellationCleanupActor(
    private val events: EventRecorder,
    private val gate: CompletableDeferred<Unit>,
) : Actor<TestCommand>() {
    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) {
        when (command) {
            is TestCommand.Block -> gate.await()
            else -> Unit
        }
    }

    override suspend fun preStop(ctx: ActorContext<TestCommand>) {
        events.record("preStop:begin")
        withTimeout(1.seconds) {
            delay(10.milliseconds)
        }
        events.record("preStop:end")
    }

    override suspend fun postStop(ctx: ActorContext<TestCommand>) {
        events.record("postStop:begin")
        withTimeout(1.seconds) {
            delay(10.milliseconds)
        }
        events.record("postStop:end")
    }
}

class FailingTeardownActor(
    private val events: EventRecorder,
    private val gate: CompletableDeferred<Unit>,
    private val failPreStop: Boolean = false,
    private val failPostStop: Boolean = false,
) : Actor<TestCommand>() {
    override suspend fun handle(
        ctx: ActorContext<TestCommand>,
        command: TestCommand,
    ) {
        when (command) {
            is TestCommand.Fail -> error("primary-boom")
            is TestCommand.Block -> gate.await()
            else -> Unit
        }
    }

    override suspend fun preStop(ctx: ActorContext<TestCommand>) {
        events.record("preStop")
        if (failPreStop) {
            error("preStop-boom")
        }
    }

    override suspend fun postStop(ctx: ActorContext<TestCommand>) {
        events.record("postStop")
        if (failPostStop) {
            error("postStop-boom")
        }
    }
}

package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle states for [PipelineRuntimeV2].
 */
enum class PipelineRuntimeV2Lifecycle {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
}

/**
 * Minimal runtime state snapshot for observability while v2 is scaffolded.
 */
data class PipelineRuntimeV2Snapshot(
    val pipelineName: String,
    val planVersion: String,
    val lifecycle: PipelineRuntimeV2Lifecycle,
)

/**
 * Actor-backed runtime scaffold with business-intent API.
 *
 * Public methods expose only runtime intent (`start`, `stop`, `snapshot`). The actor-like command
 * protocol remains private to this class.
 *
 * Atomic checkpoint and lease semantics are delegated to [DurableStore] through [PipelineRuntime]
 * and therefore keep the existing contracts unchanged.
 *
 * NOTE(v2 migration): while delegation is active, v1 runtime internals are reused as-is, including
 * its internal watchdog behavior. Full v2 extraction should remove that and keep watchdog ownership
 * exclusively at orchestrator/store level.
 */
class PipelineRuntimeV2(
    val pipeline: PipelineDefinition,
    val store: DurableStore,
    val serializer: PayloadSerializer,
    val scope: CoroutineScope,
    val planVersion: String = "v1",
) {
    private val mailbox = Channel<Command>(Channel.BUFFERED)
    private val bootstrapMutex = Mutex()
    private var lifecycle: PipelineRuntimeV2Lifecycle = PipelineRuntimeV2Lifecycle.CREATED
    private var delegate: PipelineRuntime? = null
    private var controlLoopJob: Job? = null

    /**
     * Starts this pipeline runtime. Idempotent when already running.
     */
    suspend fun start(config: RuntimeConfig = RuntimeConfig()) {
        ensureBootstrapped()
        val reply = CompletableDeferred<Unit>()
        mailbox.send(Command.Start(config, reply))
        reply.await()
    }

    /**
     * Stops this pipeline runtime. Idempotent when already stopped.
     */
    suspend fun stop() {
        ensureBootstrapped()
        val reply = CompletableDeferred<Unit>()
        mailbox.send(Command.Stop(reply))
        reply.await()
    }

    /**
     * Returns runtime lifecycle snapshot.
     */
    suspend fun snapshot(): PipelineRuntimeV2Snapshot {
        ensureBootstrapped()
        val reply = CompletableDeferred<PipelineRuntimeV2Snapshot>()
        mailbox.send(Command.Snapshot(reply))
        return reply.await()
    }

    /**
     * Graceful runtime shutdown for owner cleanup.
     */
    suspend fun shutdown() {
        ensureBootstrapped()
        val reply = CompletableDeferred<Unit>()
        mailbox.send(Command.Shutdown(reply))
        reply.await()
        controlLoopJob?.join()
    }

    private suspend fun ensureBootstrapped() {
        bootstrapMutex.withLock {
            if (controlLoopJob != null) return
            controlLoopJob =
                scope.launch(CoroutineName("pipekt-runtime-v2-${pipeline.name}")) {
                    for (command in mailbox) {
                        when (command) {
                            is Command.Start -> onStart(command)
                            is Command.Stop -> onStop(command)
                            is Command.Snapshot -> command.reply.complete(snapshotInternal())
                            is Command.Shutdown -> onShutdown(command)
                        }
                    }
                }
        }
    }

    private suspend fun onStart(command: Command.Start) {
        when (lifecycle) {
            PipelineRuntimeV2Lifecycle.STARTING,
            PipelineRuntimeV2Lifecycle.RUNNING,
            -> {
                command.reply.complete(Unit)
                return
            }
            PipelineRuntimeV2Lifecycle.CREATED,
            PipelineRuntimeV2Lifecycle.STOPPED,
            PipelineRuntimeV2Lifecycle.STOPPING,
            -> Unit
        }

        lifecycle = PipelineRuntimeV2Lifecycle.STARTING

        // TODO(v2): replace delegate with explicit child pipeline components and remove inherited
        // per-runtime watchdog behavior from v1 delegate usage.
        val runtime =
            PipelineRuntime(
                pipeline = pipeline,
                store = store,
                serializer = serializer,
                scope = scope,
                planVersion = planVersion,
            )

        try {
            runtime.start(command.config)
            delegate = runtime
            lifecycle = PipelineRuntimeV2Lifecycle.RUNNING
            command.reply.complete(Unit)
        } catch (t: Throwable) {
            lifecycle = PipelineRuntimeV2Lifecycle.STOPPED
            command.reply.completeExceptionally(t)
        }
    }

    private suspend fun onStop(command: Command.Stop) {
        when (lifecycle) {
            PipelineRuntimeV2Lifecycle.CREATED,
            PipelineRuntimeV2Lifecycle.STOPPED,
            -> {
                command.reply.complete(Unit)
                return
            }
            PipelineRuntimeV2Lifecycle.STOPPING -> {
                command.reply.complete(Unit)
                return
            }
            PipelineRuntimeV2Lifecycle.STARTING,
            PipelineRuntimeV2Lifecycle.RUNNING,
            -> Unit
        }

        lifecycle = PipelineRuntimeV2Lifecycle.STOPPING
        try {
            delegate?.stop()
            delegate = null
            lifecycle = PipelineRuntimeV2Lifecycle.STOPPED
            command.reply.complete(Unit)
        } catch (t: Throwable) {
            lifecycle = PipelineRuntimeV2Lifecycle.STOPPED
            command.reply.completeExceptionally(t)
        }
    }

    private suspend fun onShutdown(command: Command.Shutdown) {
        try {
            val stopReply = CompletableDeferred<Unit>()
            onStop(Command.Stop(stopReply))
            stopReply.await()
            mailbox.close()
            command.reply.complete(Unit)
        } catch (t: Throwable) {
            command.reply.completeExceptionally(t)
        }
    }

    private fun snapshotInternal(): PipelineRuntimeV2Snapshot =
        PipelineRuntimeV2Snapshot(
            pipelineName = pipeline.name,
            planVersion = planVersion,
            lifecycle = lifecycle,
        )

    private sealed interface Command {
        data class Start(
            val config: RuntimeConfig,
            val reply: CompletableDeferred<Unit>,
        ) : Command

        data class Stop(
            val reply: CompletableDeferred<Unit>,
        ) : Command

        data class Snapshot(
            val reply: CompletableDeferred<PipelineRuntimeV2Snapshot>,
        ) : Command

        data class Shutdown(
            val reply: CompletableDeferred<Unit>,
        ) : Command
    }
}

package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.runtime.PipelineLifecycle.NEW
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
 * Actor-backed runtime scaffold.
 *
 * Public API is intentionally small:
 * - [start] starts runtime execution and returns a [RuntimeRef]
 * - [shutdown] fully terminates runtime internals
 *
 * Pipeline operations after start are performed through [RuntimeRef].
 */
internal class PipelineRuntimeV2(
    val pipeline: PipelineDefinition,
    val store: DurableStore,
    val serializer: PayloadSerializer,
    private val runtimeScope: CoroutineScope,
    val planVersion: String = "v1",
) {
    companion object {
        suspend fun spawn(
            pipeline: PipelineDefinition,
            planVersion: String,
            deps: RuntimeDeps,
            config: RuntimeConfig = RuntimeConfig(),
        ): RuntimeRef {
            val runtime =
                PipelineRuntimeV2(
                    pipeline = pipeline,
                    planVersion = planVersion,
                    runtimeScope = deps.scope,
                    store = deps.store,
                    serializer = deps.serializer,
                )
            return runtime.start(config)
        }
    }

    private val mailbox = Channel<Command>(Channel.BUFFERED)
    private val lifecycleMutex = Mutex()

    private var lifecycle: PipelineLifecycle = NEW
    private var delegate: PipelineRuntime? = null
    private var commandsLoop: Job? = null
    private var nextHandleId: Int = 0
    private var activeHandle: RuntimeRef? = null

    /**
     * Starts runtime command loop (if needed), starts execution, and returns a pipeline handle.
     *
     * Idempotent while running: returns the existing active handle.
     */
    internal suspend fun start(config: RuntimeConfig = RuntimeConfig()): RuntimeRef {
        lifecycleMutex.withLock {
            if (commandsLoop == null) {
                commandsLoop =
                    runtimeScope.launch(CoroutineName("runtime-${pipeline.name}")) {
                        for (command in mailbox) {
                            when (command) {
                                is Command.StartRuntime -> onStartRuntime(command)
                                is Command.StopByHandle -> onStopByHandle(command)
                                is Command.SnapshotByHandle -> onSnapshotByHandle(command)
                                is Command.ShutdownRuntime -> onShutdownRuntime(command)
                            }
                        }
                    }
            }
        }
        return request(mailbox) { Command.StartRuntime(config, it) }
    }

    /**
     * Fully terminates runtime internals and closes its mailbox.
     */
    suspend fun shutdown() {
        ensureStarted()
        request(mailbox) { Command.ShutdownRuntime(it) }
        commandsLoop?.join()
    }

    /**
     * Guard-only check: verifies runtime execution is currently running.
     */
    private fun ensureStarted() {
        check(lifecycle == PipelineLifecycle.RUNNING) {
            "PipelineRuntimeV2 is not running. Call start() first."
        }
    }

    private suspend fun onStartRuntime(command: Command.StartRuntime) {
        if (lifecycle == PipelineLifecycle.RUNNING) {
            command.reply.complete(requireNotNull(activeHandle))
            return
        }
        if (lifecycle == PipelineLifecycle.SHUTDOWN) {
            command.reply.completeExceptionally(
                IllegalStateException("PipelineRuntimeV2 is shutdown and cannot restart."),
            )
            return
        }

        // TODO(v2): replace delegate with explicit child pipeline components and remove inherited
        // per-runtime watchdog behavior from v1 delegate usage.
        val runtime =
            PipelineRuntime(
                pipeline = pipeline,
                store = store,
                serializer = serializer,
                scope = runtimeScope,
                planVersion = planVersion,
            )

        completeSafe(command.reply) {
            runtime.start(command.config)
            delegate = runtime
            lifecycle = PipelineLifecycle.RUNNING
            val handleId = "rt-${++nextHandleId}"
            val handle =
                RuntimeRef(
                    id = handleId,
                    name = pipeline.name,
                    runtime = this,
                )
            activeHandle = handle
            handle
        }
    }

    private suspend fun onStopByHandle(command: Command.StopByHandle) {
        if (activeHandle?.id != command.handleId || lifecycle != PipelineLifecycle.RUNNING) {
            command.reply.complete(Unit)
            return
        }

        completeSafe(command.reply) {
            delegate?.stop()
            delegate = null
            lifecycle = PipelineLifecycle.SHUTDOWN
        }
    }

    private suspend fun onSnapshotByHandle(command: Command.SnapshotByHandle) {
        if (activeHandle?.id != command.handleId) {
            command.reply.completeExceptionally(
                IllegalStateException("Pipeline handle is not active: ${command.handleId}"),
            )
            return
        }
        completeSafe(command.reply) {
            PipelineSnapshot(
                pipelineName = pipeline.name,
                planVersion = planVersion,
                lifecycle = lifecycle,
            )
        }
    }

    private suspend fun onShutdownRuntime(command: Command.ShutdownRuntime) {
        completeSafe(command.reply) {
            if (lifecycle == PipelineLifecycle.RUNNING) {
                ensureStarted()
                delegate?.stop()
            }
            delegate = null
            activeHandle = null
            lifecycle = PipelineLifecycle.SHUTDOWN
            mailbox.close()
            Unit
        }
    }

    private suspend fun stopFromHandle(handleId: String) {
        ensureStarted()
        request(mailbox) { Command.StopByHandle(handleId, it) }
    }

    private suspend fun snapshotFromHandle(handleId: String): PipelineSnapshot {
        ensureStarted()
        return request(mailbox) { Command.SnapshotByHandle(handleId, it) }
    }

    private sealed interface Command {
        data class StartRuntime(
            val config: RuntimeConfig,
            val reply: CompletableDeferred<RuntimeRef>,
        ) : Command

        data class StopByHandle(
            val handleId: String,
            val reply: CompletableDeferred<Unit>,
        ) : Command

        data class SnapshotByHandle(
            val handleId: String,
            val reply: CompletableDeferred<PipelineSnapshot>,
        ) : Command

        data class ShutdownRuntime(
            val reply: CompletableDeferred<Unit>,
        ) : Command
    }
}

package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Orchestrates lifecycle for multiple [PipelineRuntimeV2] instances.
 *
 * Public API is business-intent only.
 */
class PipelineOrchestrator(
    val store: DurableStore,
    val serializer: PayloadSerializer,
    val config: OrchestratorConfig = OrchestratorConfig(),
    externalScope: CoroutineScope? = null,
) {
    enum class Lifecycle {
        NEW,
        RUNNING,
        SHUTDOWN,
    }

    private val ownsScope: Boolean = externalScope == null
    private val orchestratorScope: CoroutineScope =
        externalScope
            ?: CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName("pipekt-orchestrator"),
            )

    private val mailbox = Channel<Command>(Channel.BUFFERED)
    private val lifecycleMutex = Mutex()

    private val runtimeByPipeline = mutableMapOf<String, RuntimeRef>()

    private var lifecycle: Lifecycle = Lifecycle.NEW
    private var commandsLoop: Job? = null
    private var watchdog: Job? = null

    /** Starts command/watchdog loops. Idempotent while running. */
    suspend fun start() {
        lifecycleMutex.withLock {
            if (lifecycle == Lifecycle.RUNNING) return
            check(lifecycle != Lifecycle.SHUTDOWN) {
                "PipelineOrchestrator cannot restart after shutdown; create a new instance."
            }

            commandsLoop =
                orchestratorScope.launch(CoroutineName("pipekt-orchestrator-control")) {
                    for (command in mailbox) {
                        when (command) {
                            is Command.StartPipeline -> onStartPipeline(command)
                            is Command.StopPipelineByName -> onStopPipelineByName(command)
                            is Command.StopPipelineByHandleId -> onStopPipelineByHandleId(command)
                            is Command.SnapshotByHandleId -> onSnapshotByHandleId(command)
                            is Command.ListPipelines -> command.reply.complete(runtimeByPipeline.keys.toSet())
                            is Command.Shutdown -> onShutdown(command)
                        }
                    }
                }

            watchdog =
                orchestratorScope.launch(CoroutineName("pipekt-orchestrator-watchdog")) {
                    while (isActive) {
                        delay(config.watchdogInterval)
                        // One store-level reclaim loop per orchestrator instance.
                        store.reclaimExpiredLeases(now = Clock.System.now(), limit = config.reclaimLimit)
                    }
                }

            lifecycle = Lifecycle.RUNNING
        }
    }

    /** Stops all pipelines and closes orchestrator internals permanently. */
    suspend fun shutdown() {
        ensureStarted()
        request(mailbox) { Command.Shutdown(it) }
        commandsLoop?.join()
    }

    /** Starts pipeline runtime (or returns existing active handle). */
    suspend fun startPipeline(
        definition: PipelineDefinition,
        planVersion: String,
        config: RuntimeConfig = RuntimeConfig(),
    ): RuntimeRef {
        ensureStarted()
        return request(mailbox) {
            Command.StartPipeline(definition, planVersion, config, it)
        }
    }

    /** Stops active runtime by pipeline name (idempotent). */
    suspend fun stopPipeline(pipelineName: String) {
        ensureStarted()
        request(mailbox) { Command.StopPipelineByName(pipelineName, it) }
    }

    /** Lists active pipeline names. */
    suspend fun listPipelines(): Set<String> {
        ensureStarted()
        return request(mailbox) { Command.ListPipelines(it) }
    }

    private fun ensureStarted() {
        check(lifecycle == Lifecycle.RUNNING) {
            "PipelineOrchestrator is not started. Call start() first."
        }
    }

    private suspend fun onStartPipeline(command: Command.StartPipeline) {
        val existing = runtimeByPipeline[command.definition.name]
        if (existing != null) {
            command.reply.complete(existing)
            return
        }

        completeSafe(command.reply) {
            val runtime =
                PipelineRuntimeV2.spawn(
                    pipeline = command.definition,
                    planVersion = command.planVersion,
                    deps =
                        RuntimeDeps(
                            store = store,
                            serializer = serializer,
                            scope = orchestratorScope,
                        ),
                )

            runtimeByPipeline[command.definition.name] = runtime
            runtime
        }
    }

    private suspend fun onStopPipelineByName(command: Command.StopPipelineByName) {
        val handleId = handleIdByPipeline.remove(command.pipelineName)
        if (handleId == null) {
            command.reply.complete(Unit)
            return
        }

        val active = activeByHandleId.remove(handleId)
        completeSafe(command.reply) {
            active?.handle?.stop()
            Unit
        }
    }

    private suspend fun onStopPipelineByHandleId(command: Command.StopPipelineByHandleId) {
        val active = activeByHandleId.remove(command.handleId)
        if (active == null) {
            command.reply.complete(Unit)
            return
        }

        handleIdByPipeline.remove(active.handle.name)
        completeSafe(command.reply) {
            active.handle.stop()
            Unit
        }
    }

    private suspend fun onSnapshotByHandleId(command: Command.SnapshotByHandleId) {
        val active = activeByHandleId[command.handleId]
        if (active == null) {
            command.reply.completeExceptionally(
                IllegalStateException("Pipeline handle is not active: ${command.handleId}"),
            )
            return
        }

        completeSafe(command.reply) {
            active.handle.snapshot()
        }
    }

    private suspend fun onShutdown(command: Command.Shutdown) {
        val active = activeByHandleId.values.toList()
        activeByHandleId.clear()
        handleIdByPipeline.clear()

        for (entry in active) {
            entry.runtime.shutdown()
        }

        watchdog?.cancel()
        watchdog?.join()
        mailbox.close()
        lifecycle = Lifecycle.SHUTDOWN
        if (ownsScope) {
            orchestratorScope.cancel()
        }
        command.reply.complete(Unit)
    }

    /** Internal command protocol consumed by orchestrator control loop. */
    private sealed interface Command {
        data class StartPipeline(
            val definition: PipelineDefinition,
            val planVersion: String,
            val config: RuntimeConfig,
            val reply: CompletableDeferred<RuntimeRef>,
        ) : Command

        data class StopPipelineByName(
            val pipelineName: String,
            val reply: CompletableDeferred<Unit>,
        ) : Command

        data class StopPipelineByHandleId(
            val handleId: String,
            val reply: CompletableDeferred<Unit>,
        ) : Command

        data class SnapshotByHandleId(
            val handleId: String,
            val reply: CompletableDeferred<PipelineSnapshot>,
        ) : Command

        data class ListPipelines(
            val reply: CompletableDeferred<Set<String>>,
        ) : Command

        data class Shutdown(
            val reply: CompletableDeferred<Unit>,
        ) : Command
    }
}

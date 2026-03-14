package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Orchestrates runtime lifecycle for multiple pipelines.
 *
 * Public API is business-intent only. Actor-like command handling is internal and serialized
 * through a single control loop.
 */
class PipelineOrchestrator(
    val store: DurableStore,
    val serializer: PayloadSerializer,
    val scope: CoroutineScope,
    val config: OrchestratorConfig = OrchestratorConfig(),
    val runtimeFactory: PipelineRuntimeV2Factory = DefaultPipelineRuntimeV2Factory,
) {
    private val mailbox = Channel<Command>(Channel.BUFFERED)
    private val lifecycleMutex = Mutex()
    private val activeByHandleId = mutableMapOf<String, ActiveRuntime>()
    private val handleIdByPipeline = mutableMapOf<String, String>()

    private var nextHandleId: Int = 0
    private var started: Boolean = false
    private var stopped: Boolean = false
    private var controlLoopJob: Job? = null
    private var watchdogJob: Job? = null

    /** Starts control and watchdog loops. */
    suspend fun start() {
        lifecycleMutex.withLock {
            if (started) return
            check(!stopped) { "PipelineOrchestrator cannot restart after stop(); create a new instance." }

            controlLoopJob =
                scope.launch(CoroutineName("pipekt-orchestrator-control")) {
                    for (command in mailbox) {
                        when (command) {
                            is Command.StartPipeline -> onStartPipeline(command)
                            is Command.StopPipelineByName -> onStopPipelineByName(command)
                            is Command.StopPipelineByHandleId -> onStopPipelineByHandleId(command)
                            is Command.SnapshotByHandleId -> onSnapshotByHandleId(command)
                            is Command.ListPipelines -> command.reply.complete(handleIdByPipeline.keys.toSet())
                            is Command.Shutdown -> onShutdown(command)
                        }
                    }
                }

            watchdogJob =
                scope.launch(CoroutineName("pipekt-orchestrator-watchdog")) {
                    while (isActive) {
                        delay(config.watchdogInterval)
                        // One store-level reclaim loop per orchestrator instance.
                        store.reclaimExpiredLeases(now = Clock.System.now(), limit = config.reclaimLimit)
                    }
                }

            started = true
        }
    }

    /** Stops all active pipelines and orchestrator loops. */
    suspend fun stop() {
        lifecycleMutex.withLock {
            if (!started) return
        }

        val reply = CompletableDeferred<Unit>()
        mailbox.send(Command.Shutdown(reply))
        reply.await()
        controlLoopJob?.join()

        lifecycleMutex.withLock {
            started = false
            stopped = true
        }
    }

    /**
     * Starts (or returns existing) runtime for [definition].
     */
    suspend fun startPipeline(
        definition: PipelineDefinition,
        planVersion: String,
        runtimeConfig: RuntimeConfig = RuntimeConfig(),
    ): PipelineHandle {
        ensureStarted()
        val reply = CompletableDeferred<PipelineHandle>()
        mailbox.send(Command.StartPipeline(definition, planVersion, runtimeConfig, reply))
        return reply.await()
    }

    /** Stops runtime by pipeline name (idempotent). */
    suspend fun stopPipeline(pipelineName: String) {
        ensureStarted()
        val reply = CompletableDeferred<Unit>()
        mailbox.send(Command.StopPipelineByName(pipelineName, reply))
        reply.await()
    }

    /** Lists active pipeline names. */
    suspend fun listPipelines(): Set<String> {
        ensureStarted()
        val reply = CompletableDeferred<Set<String>>()
        mailbox.send(Command.ListPipelines(reply))
        return reply.await()
    }

    private suspend fun ensureStarted() {
        lifecycleMutex.withLock {
            check(started && !stopped) { "PipelineOrchestrator is not started. Call start() before using it." }
        }
    }

    private suspend fun onStartPipeline(command: Command.StartPipeline) {
        val existingHandleId = handleIdByPipeline[command.definition.name]
        if (existingHandleId != null) {
            command.reply.complete(activeByHandleId.getValue(existingHandleId).handle)
            return
        }

        val handleId = "v2-${++nextHandleId}"
        val runtime =
            runtimeFactory.create(
                definition = command.definition,
                planVersion = command.planVersion,
                dependencies = PipelineRuntimeV2Dependencies(store = store, serializer = serializer, scope = scope),
            )

        try {
            runtime.start(command.runtimeConfig)
            val handle = ManagedPipelineHandle(this, handleId, command.definition.name)
            val active = ActiveRuntime(handle = handle, runtime = runtime)
            activeByHandleId[handleId] = active
            handleIdByPipeline[handle.pipelineName] = handleId
            command.reply.complete(handle)
        } catch (t: Throwable) {
            command.reply.completeExceptionally(t)
        }
    }

    private suspend fun onStopPipelineByName(command: Command.StopPipelineByName) {
        val handleId = handleIdByPipeline.remove(command.pipelineName)
        if (handleId == null) {
            command.reply.complete(Unit)
            return
        }

        val active = activeByHandleId.remove(handleId)
        active?.runtime?.stop()
        command.reply.complete(Unit)
    }

    private suspend fun onStopPipelineByHandleId(command: Command.StopPipelineByHandleId) {
        val active = activeByHandleId.remove(command.handleId)
        if (active == null) {
            command.reply.complete(Unit)
            return
        }

        handleIdByPipeline.remove(active.handle.pipelineName)
        active.runtime.stop()
        command.reply.complete(Unit)
    }

    private suspend fun onSnapshotByHandleId(command: Command.SnapshotByHandleId) {
        val active = activeByHandleId[command.handleId]
        if (active == null) {
            command.reply.completeExceptionally(
                IllegalStateException("Pipeline handle is not active: ${command.handleId}"),
            )
            return
        }
        try {
            command.reply.complete(active.runtime.snapshot())
        } catch (t: Throwable) {
            command.reply.completeExceptionally(t)
        }
    }

    private suspend fun onShutdown(command: Command.Shutdown) {
        val active = activeByHandleId.values.toList()
        activeByHandleId.clear()
        handleIdByPipeline.clear()

        for (entry in active) {
            entry.runtime.stop()
        }

        watchdogJob?.cancel()
        watchdogJob?.join()
        mailbox.close()
        command.reply.complete(Unit)
    }

    private suspend fun stopByHandleId(handleId: String) {
        ensureStarted()
        val reply = CompletableDeferred<Unit>()
        mailbox.send(Command.StopPipelineByHandleId(handleId, reply))
        reply.await()
    }

    private suspend fun snapshotByHandleId(handleId: String): PipelineRuntimeV2Snapshot {
        ensureStarted()
        val reply = CompletableDeferred<PipelineRuntimeV2Snapshot>()
        mailbox.send(Command.SnapshotByHandleId(handleId, reply))
        return reply.await()
    }

    private class ManagedPipelineHandle(
        private val orchestrator: PipelineOrchestrator,
        private val handleId: String,
        override val pipelineName: String,
    ) : PipelineHandle {
        override suspend fun stop() {
            orchestrator.stopByHandleId(handleId)
        }

        override suspend fun snapshot(): PipelineRuntimeV2Snapshot =
            orchestrator.snapshotByHandleId(handleId)
    }

    private data class ActiveRuntime(
        val handle: PipelineHandle,
        val runtime: PipelineRuntimeV2,
    )

    private sealed interface Command {
        data class StartPipeline(
            val definition: PipelineDefinition,
            val planVersion: String,
            val runtimeConfig: RuntimeConfig,
            val reply: CompletableDeferred<PipelineHandle>,
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
            val reply: CompletableDeferred<PipelineRuntimeV2Snapshot>,
        ) : Command

        data class ListPipelines(
            val reply: CompletableDeferred<Set<String>>,
        ) : Command

        data class Shutdown(
            val reply: CompletableDeferred<Unit>,
        ) : Command
    }
}

private object DefaultPipelineRuntimeV2Factory : PipelineRuntimeV2Factory {
    override fun create(
        definition: PipelineDefinition,
        planVersion: String,
        dependencies: PipelineRuntimeV2Dependencies,
    ): PipelineRuntimeV2 =
        PipelineRuntimeV2(
            pipeline = definition,
            store = dependencies.store,
            serializer = dependencies.serializer,
            scope = dependencies.scope,
            planVersion = planVersion,
        )
}

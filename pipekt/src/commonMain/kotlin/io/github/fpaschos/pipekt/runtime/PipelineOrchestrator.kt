package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.ActorRef
import io.github.fpaschos.pipekt.actor.ActorTermination
import io.github.fpaschos.pipekt.actor.ReplyChannel
import io.github.fpaschos.pipekt.actor.Request
import io.github.fpaschos.pipekt.actor.ask
import io.github.fpaschos.pipekt.actor.spawn
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Public entry point for running and managing active pipelines for a store.
 */
class PipelineOrchestrator(
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
    private val config: OrchestratorConfig = OrchestratorConfig(),
    externalScope: CoroutineScope? = null,
) {
    private val ownsScope: Boolean = externalScope == null
    private val orchestratorScope: CoroutineScope =
        externalScope
            ?: CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName("pipekt-orchestrator"),
            )

    private val stateMutex = Mutex()
    private var state: OrchestratorState = OrchestratorState.NEW
    private var actorRef: ActorRef<OrchestratorCommand>? = null
    private val handleController =
        object : RuntimeHandleController {
            override suspend fun stopHandle(handleId: String) {
                this@PipelineOrchestrator.stopHandle(handleId)
            }

            override suspend fun snapshotHandle(handleId: String): PipelineSnapshot =
                this@PipelineOrchestrator.snapshotHandle(handleId)
        }

    suspend fun startPipeline(
        definition: PipelineDefinition,
        planVersion: String,
        config: RuntimeConfig = RuntimeConfig(),
    ): RuntimeHandle =
        actorRef()
            .ask(ASK_TIMEOUT) { replyTo ->
                OrchestratorCommand.StartPipeline(
                    definition = definition,
                    planVersion = planVersion,
                    config = config,
                    replyTo = replyTo,
                )
            }.getOrThrow()

    suspend fun stopPipeline(pipelineName: String) {
        actorRef()
            .ask(ASK_TIMEOUT) { replyTo ->
                OrchestratorCommand.StopPipelineByName(pipelineName, replyTo)
            }.getOrThrow()
    }

    suspend fun listPipelines(): Set<String> =
        actorRef()
            .ask(ASK_TIMEOUT) { replyTo ->
                OrchestratorCommand.ListPipelines(replyTo)
            }.getOrThrow()

    suspend fun shutdown() {
        val ref =
            stateMutex.withLock {
                if (state == OrchestratorState.SHUTDOWN) {
                    null
                } else {
                    state = OrchestratorState.SHUTDOWN
                    actorRef.also { actorRef = null }
                }
            }

        try {
            ref?.shutdown(timeout = ASK_TIMEOUT)
        } finally {
            if (ownsScope) {
                orchestratorScope.cancel()
            }
        }
    }

    private suspend fun stopHandle(handleId: String) {
        actorRef()
            .ask(ASK_TIMEOUT) { replyTo ->
                OrchestratorCommand.StopPipelineByHandleId(handleId, replyTo)
            }.getOrThrow()
    }

    private suspend fun snapshotHandle(handleId: String): PipelineSnapshot =
        actorRef()
            .ask(ASK_TIMEOUT) { replyTo ->
                OrchestratorCommand.SnapshotByHandleId(handleId, replyTo)
            }.getOrThrow()

    private suspend fun actorRef(): ActorRef<OrchestratorCommand> {
        val existing =
            stateMutex.withLock {
                check(state != OrchestratorState.SHUTDOWN) {
                    "PipelineOrchestrator is shutdown and cannot be used again."
                }
                actorRef
            }
        if (existing != null) {
            return existing
        }

        return stateMutex.withLock {
            check(state != OrchestratorState.SHUTDOWN) {
                "PipelineOrchestrator is shutdown and cannot be used again."
            }

            actorRef ?: withContext(orchestratorScope.coroutineContext) {
                spawn("pipekt-orchestrator") {
                    PipelineOrchestratorActor(
                        store = store,
                        serializer = serializer,
                        config = config,
                        handleController = handleController,
                    )
                }
            }.also {
                actorRef = it
                state = OrchestratorState.RUNNING
            }
        }
    }

    private enum class OrchestratorState {
        NEW,
        RUNNING,
        SHUTDOWN,
    }

    private companion object {
        private val ASK_TIMEOUT: Duration = 30.seconds
    }
}

private sealed interface OrchestratorCommand {
    class StartPipeline(
        val definition: PipelineDefinition,
        val planVersion: String,
        val config: RuntimeConfig,
        replyTo: ReplyChannel<RuntimeHandle>,
    ) : Request<RuntimeHandle>(replyTo),
        OrchestratorCommand

    class StopPipelineByName(
        val pipelineName: String,
        replyTo: ReplyChannel<Unit>,
    ) : Request<Unit>(replyTo),
        OrchestratorCommand

    class StopPipelineByHandleId(
        val handleId: String,
        replyTo: ReplyChannel<Unit>,
    ) : Request<Unit>(replyTo),
        OrchestratorCommand

    class SnapshotByHandleId(
        val handleId: String,
        replyTo: ReplyChannel<PipelineSnapshot>,
    ) : Request<PipelineSnapshot>(replyTo),
        OrchestratorCommand

    class ListPipelines(
        replyTo: ReplyChannel<Set<String>>,
    ) : Request<Set<String>>(replyTo),
        OrchestratorCommand

    data class RuntimeTerminated(
        val termination: ActorTermination,
    ) : OrchestratorCommand
}

private class PipelineOrchestratorActor(
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
    private val config: OrchestratorConfig,
    private val handleController: RuntimeHandleController,
) : Actor<OrchestratorCommand>() {
    private lateinit var actorScope: CoroutineScope

    private val activeRuntimes = mutableMapOf<String, ActiveRuntime>()
    private var watchdogJob: Job? = null
    private var nextHandleId: Long = 0

    override suspend fun postStart(ctx: ActorContext<OrchestratorCommand>) {
        actorScope =
            CoroutineScope(
                currentCoroutineContext() +
                    SupervisorJob(currentCoroutineContext()[Job]) +
                    CoroutineName("${ctx.label}/orchestrator"),
            )
        watchdogJob =
            actorScope.launch(CoroutineName("${ctx.label}/watchdog")) {
                while (isActive) {
                    delay(config.watchdogInterval)
                    store.reclaimExpiredLeases(Clock.System.now(), config.reclaimLimit)
                }
            }
    }

    override suspend fun handle(
        ctx: ActorContext<OrchestratorCommand>,
        command: OrchestratorCommand,
    ) {
        when (command) {
            is OrchestratorCommand.StartPipeline -> handleStartPipeline(ctx, command)
            is OrchestratorCommand.StopPipelineByName -> handleStopPipelineByName(command)
            is OrchestratorCommand.StopPipelineByHandleId -> handleStopPipelineByHandleId(command)
            is OrchestratorCommand.SnapshotByHandleId -> handleSnapshotByHandleId(command)
            is OrchestratorCommand.ListPipelines -> command.success(activeRuntimes.keys.toSet())
            is OrchestratorCommand.RuntimeTerminated -> handleRuntimeTerminated(command)
        }
    }

    override suspend fun preStop(ctx: ActorContext<OrchestratorCommand>) {
        val runtimes = activeRuntimes.values.toList()
        activeRuntimes.clear()

        runtimes.forEach { it.actor.shutdown(timeout = null) }

        watchdogJob?.cancel()
        watchdogJob?.join()
    }

    private suspend fun handleStartPipeline(
        ctx: ActorContext<OrchestratorCommand>,
        command: OrchestratorCommand.StartPipeline,
    ) {
        val existing = activeRuntimes[command.definition.name]
        if (existing != null) {
            if (existing.handle.planVersion != command.planVersion) {
                command.failure(
                    IllegalStateException(
                        "Pipeline ${command.definition.name} is already active with planVersion " +
                            "${existing.handle.planVersion}; stop it before starting ${command.planVersion}.",
                    ),
                )
                return
            }

            command.success(existing.handle)
            return
        }

        val runtimeActor =
            spawn(name = "pipeline-${command.definition.name}") {
                PipelineRuntimeActor(
                    pipeline = command.definition,
                    store = store,
                    serializer = serializer,
                    planVersion = command.planVersion,
                    config = command.config,
                )
            }
        ctx.watch(runtimeActor) { termination ->
            OrchestratorCommand.RuntimeTerminated(termination)
        }

        val snapshot =
            runtimeActor
                .ask(30.seconds) { replyTo -> RuntimeCommand.Snapshot(replyTo) }
                .getOrThrow()

        val handle =
            RuntimeHandle(
                id = "runtime-${++nextHandleId}",
                pipelineName = snapshot.pipelineName,
                planVersion = snapshot.planVersion,
                runId = snapshot.runId,
                controller = handleController,
            )

        activeRuntimes[command.definition.name] = ActiveRuntime(handle = handle, actor = runtimeActor)
        command.success(handle)
    }

    private suspend fun handleStopPipelineByName(command: OrchestratorCommand.StopPipelineByName) {
        val active = activeRuntimes.remove(command.pipelineName)
        if (active == null) {
            command.success(Unit)
            return
        }

        active.actor.shutdown(timeout = null)
        command.success(Unit)
    }

    private suspend fun handleStopPipelineByHandleId(command: OrchestratorCommand.StopPipelineByHandleId) {
        val entry =
            activeRuntimes.entries.firstOrNull { (_, active) ->
                active.handle.id == command.handleId
            }
        if (entry == null) {
            command.success(Unit)
            return
        }

        activeRuntimes.remove(entry.key)
        entry.value.actor.shutdown(timeout = null)
        command.success(Unit)
    }

    private suspend fun handleSnapshotByHandleId(command: OrchestratorCommand.SnapshotByHandleId) {
        val active =
            activeRuntimes.values.firstOrNull { it.handle.id == command.handleId }
                ?: run {
                    command.failure(IllegalStateException("Pipeline handle is not active: ${command.handleId}"))
                    return
                }

        val snapshot =
            active.actor
                .ask(30.seconds) { replyTo -> RuntimeCommand.Snapshot(replyTo) }
                .getOrThrow()
        command.success(snapshot)
    }

    private fun handleRuntimeTerminated(command: OrchestratorCommand.RuntimeTerminated) {
        val pipelineName =
            activeRuntimes.entries.firstOrNull { (_, active) ->
                active.actor.label == command.termination.actorLabel
            }?.key
        if (pipelineName != null) {
            activeRuntimes.remove(pipelineName)
        }
    }

    private data class ActiveRuntime(
        val handle: RuntimeHandle,
        val actor: ActorRef<RuntimeCommand>,
    )
}

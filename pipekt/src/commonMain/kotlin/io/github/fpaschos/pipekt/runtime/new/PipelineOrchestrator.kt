package io.github.fpaschos.pipekt.runtime.new

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.ActorRef
import io.github.fpaschos.pipekt.actor.ActorTermination
import io.github.fpaschos.pipekt.actor.ReplyRef
import io.github.fpaschos.pipekt.actor.ask
import io.github.fpaschos.pipekt.actor.spawn
import io.github.fpaschos.pipekt.core.KotlinxPayloadSerializer
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

interface PipelineOrchestrator {
    suspend fun startPipeline(
        definition: PipelineDefinition,
        planVersion: String,
        config: RuntimeConfig = RuntimeConfig(),
    ): PipelineExecutable

    suspend fun stopPipeline(
        executableId: String,
        timeout: Duration? = null,
    )

    suspend fun inspectPipeline(executableId: String): PipelineExecutableSnapshot?

    suspend fun listActivePipelines(): List<PipelineExecutableSnapshot>

    suspend fun stopAll(timeout: Duration? = null)

    suspend fun shutdown(timeout: Duration? = null)
}

suspend fun createPipelineOrchestrator(
    store: DurableStore,
    serializer: PayloadSerializer = KotlinxPayloadSerializer,
    dispatcher: CoroutineDispatcher? = null,
): PipelineOrchestrator {
    val ref =
        spawn<OrchestratorCommand>(
            name = "pipeline-orchestrator",
            dispatcher = dispatcher,
        ) {
            PipelineOrchestratorActor(store = store, serializer = serializer)
        }
    return DefaultPipelineOrchestrator(ref)
}

private class DefaultPipelineOrchestrator(
    private val ref: ActorRef<OrchestratorCommand>,
) : PipelineOrchestrator {
    override suspend fun startPipeline(
        definition: PipelineDefinition,
        planVersion: String,
        config: RuntimeConfig,
    ): PipelineExecutable =
        ref.ask(30.seconds) { replyTo ->
            OrchestratorCommand.StartPipeline(
                definition = definition,
                planVersion = planVersion,
                config = config,
                replyTo = replyTo,
            )
        }.getOrThrow()

    override suspend fun stopPipeline(
        executableId: String,
        timeout: Duration?,
    ) {
        ref.ask(30.seconds) { replyTo ->
            OrchestratorCommand.StopPipeline(
                executableId = executableId,
                timeout = timeout,
                replyTo = replyTo,
            )
        }.getOrThrow()
    }

    override suspend fun inspectPipeline(executableId: String): PipelineExecutableSnapshot? =
        ref.ask(30.seconds) { replyTo ->
            OrchestratorCommand.InspectPipeline(
                executableId = executableId,
                replyTo = replyTo,
            )
        }.getOrThrow()

    override suspend fun listActivePipelines(): List<PipelineExecutableSnapshot> =
        ref.ask(30.seconds) { replyTo -> OrchestratorCommand.ListActivePipelines(replyTo) }.getOrThrow()

    override suspend fun stopAll(timeout: Duration?) {
        ref.ask(30.seconds) { replyTo -> OrchestratorCommand.StopAll(timeout = timeout, replyTo = replyTo) }.getOrThrow()
    }

    override suspend fun shutdown(timeout: Duration?) {
        ref.shutdown(timeout)
    }
}

private class DefaultPipelineExecutable(
    private val orchestrator: PipelineOrchestrator,
    private var latestSnapshot: PipelineExecutableSnapshot,
) : PipelineExecutable {
    override val executableId: String
        get() = latestSnapshot.executableId
    override val pipelineName: String
        get() = latestSnapshot.pipelineName
    override val planVersion: String
        get() = latestSnapshot.planVersion
    override val runId: String
        get() = latestSnapshot.runId

    override suspend fun stop(timeout: Duration?) {
        orchestrator.stopPipeline(executableId, timeout)
    }

    override suspend fun snapshot(): PipelineExecutableSnapshot? =
        orchestrator.inspectPipeline(executableId)?.also { latestSnapshot = it }
}

private data class ActivePipeline(
    val snapshot: PipelineExecutableSnapshot,
    val runtimeRef: ActorRef<RuntimeCommand>,
)

private sealed interface OrchestratorCommand {
    data class StartPipeline(
        val definition: PipelineDefinition,
        val planVersion: String,
        val config: RuntimeConfig,
        val replyTo: ReplyRef<PipelineExecutable>,
    ) : OrchestratorCommand

    data class StopPipeline(
        val executableId: String,
        val timeout: Duration?,
        val replyTo: ReplyRef<Unit>,
    ) : OrchestratorCommand

    data class InspectPipeline(
        val executableId: String,
        val replyTo: ReplyRef<PipelineExecutableSnapshot?>,
    ) : OrchestratorCommand

    data class ListActivePipelines(
        val replyTo: ReplyRef<List<PipelineExecutableSnapshot>>,
    ) : OrchestratorCommand

    data class StopAll(
        val timeout: Duration?,
        val replyTo: ReplyRef<Unit>,
    ) : OrchestratorCommand

    data class RuntimeTerminated(
        val executableId: String,
        val termination: ActorTermination,
    ) : OrchestratorCommand

    data class LeaseReclaimerTerminated(
        val termination: ActorTermination,
    ) : OrchestratorCommand
}

private class PipelineOrchestratorActor(
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
) : Actor<OrchestratorCommand>() {
    private val activeExecutables = linkedMapOf<String, ActivePipeline>()
    private var leaseReclaimerRef: ActorRef<LeaseReclaimerCommand>? = null

    override suspend fun postStart(ctx: ActorContext<OrchestratorCommand>) {
        val leaseRef =
            spawn<LeaseReclaimerCommand>(name = "lease-reclaimer") {
                LeaseReclaimerActor(store = store, config = RuntimeConfig())
            }
        ctx.watch(leaseRef) { termination -> OrchestratorCommand.LeaseReclaimerTerminated(termination) }
        leaseReclaimerRef = leaseRef
    }

    override suspend fun handle(
        ctx: ActorContext<OrchestratorCommand>,
        command: OrchestratorCommand,
    ) {
        when (command) {
            is OrchestratorCommand.StartPipeline -> startPipeline(ctx, command)
            is OrchestratorCommand.StopPipeline -> stopPipeline(command)
            is OrchestratorCommand.InspectPipeline -> command.replyTo.tell(activeExecutables[command.executableId]?.snapshot)
            is OrchestratorCommand.ListActivePipelines -> command.replyTo.tell(activeExecutables.values.map { it.snapshot })
            is OrchestratorCommand.StopAll -> {
                activeExecutables.values.toList().forEach { active ->
                    active.runtimeRef.shutdown(command.timeout)
                }
                activeExecutables.clear()
                command.replyTo.tell(Unit)
            }

            is OrchestratorCommand.RuntimeTerminated -> {
                activeExecutables.remove(command.executableId)
            }

            is OrchestratorCommand.LeaseReclaimerTerminated -> {
                throw IllegalStateException(
                    "Lease reclaimer actor terminated unexpectedly.",
                    command.termination.cause,
                )
            }
        }
    }

    override suspend fun preStop(ctx: ActorContext<OrchestratorCommand>) {
        activeExecutables.values.toList().forEach { active ->
            active.runtimeRef.shutdown()
        }
        activeExecutables.clear()
        leaseReclaimerRef?.shutdown()
        leaseReclaimerRef = null
    }

    private suspend fun startPipeline(
        ctx: ActorContext<OrchestratorCommand>,
        command: OrchestratorCommand.StartPipeline,
    ) {
        activeExecutables.values.firstOrNull {
            it.snapshot.pipelineName == command.definition.name &&
                it.snapshot.planVersion == command.planVersion
        }?.let {
            command.replyTo.fail(
                PipelineAlreadyActiveException(
                    pipelineName = command.definition.name,
                    planVersion = command.planVersion,
                ),
            )
            return
        }

        val runtime =
            PipelineRuntime(
                definition = command.definition,
                planVersion = command.planVersion,
                store = store,
                serializer = serializer,
                config = command.config,
            )
        val runtimeRef =
            spawn<RuntimeCommand>(name = "pipeline-runtime-${command.definition.name}") {
                PipelineRuntimeActor(runtime = runtime, config = command.config)
            }
        val snapshot =
            PipelineExecutableSnapshot(
                executableId = runtimeRef.label,
                pipelineName = command.definition.name,
                planVersion = command.planVersion,
                runId = runtime.runRecord.id,
            )
        activeExecutables[snapshot.executableId] = ActivePipeline(snapshot = snapshot, runtimeRef = runtimeRef)
        ctx.watch(runtimeRef) { termination ->
            OrchestratorCommand.RuntimeTerminated(
                executableId = snapshot.executableId,
                termination = termination,
            )
        }
        command.replyTo.tell(DefaultPipelineExecutable(DefaultPipelineOrchestrator(ctx.self), snapshot))
    }

    private suspend fun stopPipeline(command: OrchestratorCommand.StopPipeline) {
        val active =
            activeExecutables[command.executableId]
                ?: throw PipelineExecutableNotFoundException(command.executableId)
        active.runtimeRef.shutdown(command.timeout)
        activeExecutables.remove(command.executableId)
        command.replyTo.tell(Unit)
    }
}

private sealed interface RuntimeCommand {
    data class WorkerTerminated(
        val termination: ActorTermination,
    ) : RuntimeCommand
}

private class PipelineRuntimeActor(
    private val runtime: PipelineRuntime,
    private val config: RuntimeConfig,
) : Actor<RuntimeCommand>() {
    private val workers = mutableListOf<ActorRef<WorkerCommand>>()

    override suspend fun postStart(ctx: ActorContext<RuntimeCommand>) {
        runtime.start()

        val ingressWorker =
            spawn<WorkerCommand>(name = "ingress-${runtime.definition.name}") {
                IngressWorkerActor(runtime = runtime, config = config)
            }
        ctx.watch(ingressWorker) { termination -> RuntimeCommand.WorkerTerminated(termination) }
        workers += ingressWorker

        runtime.stepNames.forEach { stepName ->
            val worker =
                spawn<WorkerCommand>(name = "step-${runtime.definition.name}-$stepName") {
                    StepWorkerActor(
                        runtime = runtime,
                        config = config,
                        stepName = stepName,
                    )
                }
            ctx.watch(worker) { termination -> RuntimeCommand.WorkerTerminated(termination) }
            workers += worker
        }
    }

    override suspend fun handle(
        ctx: ActorContext<RuntimeCommand>,
        command: RuntimeCommand,
    ) {
        when (command) {
            is RuntimeCommand.WorkerTerminated -> {
                throw IllegalStateException(
                    "Worker ${command.termination.actorLabel} terminated unexpectedly.",
                    command.termination.cause,
                )
            }
        }
    }

    override suspend fun preStop(ctx: ActorContext<RuntimeCommand>) {
        workers.toList().forEach { worker ->
            worker.shutdown()
        }
        workers.clear()
    }
}

private sealed interface WorkerCommand {
    data object Tick : WorkerCommand
}

private class IngressWorkerActor(
    private val runtime: PipelineRuntime,
    private val config: RuntimeConfig,
) : Actor<WorkerCommand>() {
    override suspend fun postStart(ctx: ActorContext<WorkerCommand>) {
        scheduleCommand(ctx, ZERO, WorkerCommand.Tick)
    }

    override suspend fun handle(
        ctx: ActorContext<WorkerCommand>,
        command: WorkerCommand,
    ) {
        when (command) {
            WorkerCommand.Tick -> {
                val capacity = runtime.remainingIngressCapacity()
                val processed =
                    if (capacity > 0) {
                        runtime.ingest(maxItems = minOf(capacity, config.workerClaimLimit))
                    } else {
                        false
                    }
                scheduleCommand(ctx, if (processed) ZERO else config.workerPollInterval, WorkerCommand.Tick)
            }
        }
    }
}

private class StepWorkerActor(
    private val runtime: PipelineRuntime,
    private val config: RuntimeConfig,
    private val stepName: String,
) : Actor<WorkerCommand>() {
    override suspend fun postStart(ctx: ActorContext<WorkerCommand>) {
        scheduleCommand(ctx, ZERO, WorkerCommand.Tick)
    }

    override suspend fun handle(
        ctx: ActorContext<WorkerCommand>,
        command: WorkerCommand,
    ) {
        when (command) {
            WorkerCommand.Tick -> {
                val processed = runtime.executeStep(stepName = stepName, workerId = ctx.label)
                scheduleCommand(ctx, if (processed) ZERO else config.workerPollInterval, WorkerCommand.Tick)
            }
        }
    }
}

private sealed interface LeaseReclaimerCommand {
    data object Tick : LeaseReclaimerCommand
}

private class LeaseReclaimerActor(
    private val store: DurableStore,
    private val config: RuntimeConfig,
) : Actor<LeaseReclaimerCommand>() {
    override suspend fun postStart(ctx: ActorContext<LeaseReclaimerCommand>) {
        scheduleCommand(ctx, config.watchdogInterval, LeaseReclaimerCommand.Tick)
    }

    override suspend fun handle(
        ctx: ActorContext<LeaseReclaimerCommand>,
        command: LeaseReclaimerCommand,
    ) {
        when (command) {
            LeaseReclaimerCommand.Tick -> {
                store.reclaimExpiredLeases(
                    now = Clock.System.now(),
                    limit = config.workerClaimLimit,
                )
                scheduleCommand(ctx, config.watchdogInterval, LeaseReclaimerCommand.Tick)
            }
        }
    }
}

private suspend fun <Command : Any> scheduleCommand(
    ctx: ActorContext<Command>,
    delay: Duration,
    command: Command,
) {
    CoroutineScope(currentCoroutineContext()).launch {
        if (delay > ZERO) {
            delay(delay)
        }
        ctx.self.tell(command)
    }
}

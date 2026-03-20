package io.github.fpaschos.pipekt.runtime.new

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.ActorRef
import io.github.fpaschos.pipekt.actor.ActorTermination
import io.github.fpaschos.pipekt.actor.TimerKey
import io.github.fpaschos.pipekt.actor.ask
import io.github.fpaschos.pipekt.actor.spawn
import io.github.fpaschos.pipekt.core.KotlinxPayloadSerializer
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.runtime.new.actors.LeaseReclaimer
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/*
 * Runtime ownership summary:
 * - PipelineOrchestratorActor owns the active executable registry and store-scoped coordination.
 * - PipelineRuntimeActor owns one started pipeline runtime instance.
 * - IngressWorkerActor and StepWorkerActor own polling/execution loops for that runtime.
 * - LeaseReclaimerActor owns store-scoped expired-lease reclaim.
 *
 * Registry and lifecycle mutation are actor-owned. This subsystem should remain actor-first
 * rather than reintroducing shared mutable coordination.
 */
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
        spawn(
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
    ): PipelineExecutable {
        val reply =
            ref
                .ask(30.seconds) { replyTo ->
                    OrchestratorCommand.StartPipeline(
                        definition = definition,
                        planVersion = planVersion,
                        config = config,
                        replyTo = replyTo,
                    )
                }.getOrThrow()

        return when (reply) {
            is StartPipelineReply.Started -> {
                reply.executable
            }

            is StartPipelineReply.AlreadyActive -> {
                throw PipelineAlreadyActiveException(
                    pipelineName = reply.pipelineName,
                    planVersion = reply.planVersion,
                )
            }
        }
    }

    override suspend fun stopPipeline(
        executableId: String,
        timeout: Duration?,
    ) {
        when (
            val reply =
                ref
                    .ask(30.seconds) { replyTo ->
                        OrchestratorCommand.StopPipeline(
                            executableId = executableId,
                            timeout = timeout,
                            replyTo = replyTo,
                        )
                    }.getOrThrow()
        ) {
            StopPipelineReply.Stopped -> Unit
            is StopPipelineReply.NotFound -> throw PipelineExecutableNotFoundException(reply.executableId)
        }
    }

    override suspend fun inspectPipeline(executableId: String): PipelineExecutableSnapshot? =
        when (
            val reply =
                ref
                    .ask(30.seconds) { replyTo ->
                        OrchestratorCommand.InspectPipeline(
                            executableId = executableId,
                            replyTo = replyTo,
                        )
                    }.getOrThrow()
        ) {
            is InspectPipelineReply.Found -> reply.snapshot
            InspectPipelineReply.NotFound -> null
        }

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

    override suspend fun snapshot(): PipelineExecutableSnapshot? = orchestrator.inspectPipeline(executableId)?.also { latestSnapshot = it }
}

private data class ActivePipeline(
    val snapshot: PipelineExecutableSnapshot,
    val runtimeRef: ActorRef<RuntimeCommand>,
)

private sealed interface StartPipelineReply {
    data class Started(
        val executable: PipelineExecutable,
    ) : StartPipelineReply

    data class AlreadyActive(
        val pipelineName: String,
        val planVersion: String,
    ) : StartPipelineReply
}

private sealed interface StopPipelineReply {
    data object Stopped : StopPipelineReply

    data class NotFound(
        val executableId: String,
    ) : StopPipelineReply
}

private sealed interface InspectPipelineReply {
    data class Found(
        val snapshot: PipelineExecutableSnapshot,
    ) : InspectPipelineReply

    data object NotFound : InspectPipelineReply
}

private sealed interface OrchestratorCommand {
    data class StartPipeline(
        val definition: PipelineDefinition,
        val planVersion: String,
        val config: RuntimeConfig,
        val replyTo: ActorRef<StartPipelineReply>,
    ) : OrchestratorCommand

    data class StopPipeline(
        val executableId: String,
        val timeout: Duration?,
        val replyTo: ActorRef<StopPipelineReply>,
    ) : OrchestratorCommand

    data class InspectPipeline(
        val executableId: String,
        val replyTo: ActorRef<InspectPipelineReply>,
    ) : OrchestratorCommand

    data class ListActivePipelines(
        val replyTo: ActorRef<List<PipelineExecutableSnapshot>>,
    ) : OrchestratorCommand

    data class StopAll(
        val timeout: Duration?,
        val replyTo: ActorRef<Unit>,
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
    private var leaseReclaimerRef: ActorRef<LeaseReclaimer.Command>? = null
    private var leaseReclaimerConfig: RuntimeConfig? = null

    override suspend fun handle(
        ctx: ActorContext<OrchestratorCommand>,
        command: OrchestratorCommand,
    ) {
        when (command) {
            is OrchestratorCommand.StartPipeline -> {
                startPipeline(ctx, command)
            }

            is OrchestratorCommand.StopPipeline -> {
                stopPipeline(command)
            }

            is OrchestratorCommand.InspectPipeline -> {
                command.replyTo.tell(
                    activeExecutables[command.executableId]
                        ?.snapshot
                        ?.let(InspectPipelineReply::Found)
                        ?: InspectPipelineReply.NotFound,
                )
            }

            is OrchestratorCommand.ListActivePipelines -> {
                command.replyTo.tell(activeExecutables.values.map { it.snapshot })
            }

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
        activeExecutables.values
            .firstOrNull {
                it.snapshot.pipelineName == command.definition.name &&
                    it.snapshot.planVersion == command.planVersion
            }?.let {
                command.replyTo.tell(
                    StartPipelineReply.AlreadyActive(
                        pipelineName = command.definition.name,
                        planVersion = command.planVersion,
                    ),
                )
                return
            }

        ensureLeaseReclaimer(ctx, command.config)

        val runtime =
            PipelineRuntime(
                definition = command.definition,
                planVersion = command.planVersion,
                store = store,
                serializer = serializer,
                config = command.config,
            )
        val runtimeRef =
            spawn(name = "pipeline-runtime-${command.definition.name}") {
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
        command.replyTo.tell(
            StartPipelineReply.Started(
                DefaultPipelineExecutable(DefaultPipelineOrchestrator(ctx.self), snapshot),
            ),
        )
    }

    private suspend fun stopPipeline(command: OrchestratorCommand.StopPipeline) {
        val active =
            activeExecutables[command.executableId]
                ?: run {
                    command.replyTo.tell(StopPipelineReply.NotFound(command.executableId))
                    return
                }
        active.runtimeRef.shutdown(command.timeout)
        activeExecutables.remove(command.executableId)
        command.replyTo.tell(StopPipelineReply.Stopped)
    }

    private suspend fun ensureLeaseReclaimer(
        ctx: ActorContext<OrchestratorCommand>,
        requestedConfig: RuntimeConfig,
    ) {
        val currentConfig = leaseReclaimerConfig
        val mergedConfig =
            currentConfig?.copy(
                watchdogInterval = minOf(currentConfig.watchdogInterval, requestedConfig.watchdogInterval),
                workerClaimLimit = maxOf(currentConfig.workerClaimLimit, requestedConfig.workerClaimLimit),
            )
                ?: requestedConfig

        if (leaseReclaimerRef != null && mergedConfig == currentConfig) {
            return
        }

        leaseReclaimerRef?.shutdown()
        val leaseRef =
            spawn(name = "lease-reclaimer") {
                LeaseReclaimer(store = store, config = mergedConfig)
            }
        ctx.watch(leaseRef) { termination -> OrchestratorCommand.LeaseReclaimerTerminated(termination) }
        leaseReclaimerRef = leaseRef
        leaseReclaimerConfig = mergedConfig
    }
}

private sealed interface RuntimeCommand {
    data class WorkerTerminated(
        val termination: ActorTermination,
    ) : RuntimeCommand
}

/**
 * Owns one started [PipelineRuntime] and its worker actors.
 *
 * This actor does not accept external user traffic after startup. It only reacts to watch
 * notifications from owned workers, which are delivered on the actor system queue rather than the
 * user mailbox. `capacity = 0` makes that intent explicit and avoids an unnecessary buffered user
 * mailbox that nothing should write to.
 */
private class PipelineRuntimeActor(
    private val runtime: PipelineRuntime,
    private val config: RuntimeConfig,
) : Actor<RuntimeCommand>(capacity = 0) {
    private val workers = mutableListOf<ActorRef<WorkerCommand>>()

    override suspend fun postStart(ctx: ActorContext<RuntimeCommand>) {
        runtime.start()

        val ingressWorker =
            spawn(name = "ingress-${runtime.definition.name}") {
                IngressWorkerActor(runtime = runtime, config = config)
            }
        ctx.watch(ingressWorker) { termination -> RuntimeCommand.WorkerTerminated(termination) }
        workers += ingressWorker

        runtime.stepNames.forEach { stepName ->
            val worker =
                spawn(name = "step-${runtime.definition.name}-$stepName") {
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

/**
 * Drives ingress polling for one pipeline runtime.
 *
 * Work is scheduled entirely by self-timers. No external actor should enqueue user commands here,
 * so `capacity = 0` documents that the user mailbox is intentionally unused while timer events keep
 * flowing through the internal system queue.
 */
private class IngressWorkerActor(
    private val runtime: PipelineRuntime,
    private val config: RuntimeConfig,
) : Actor<WorkerCommand>(capacity = 0) {
    private val tickTimerKey = TimerKey("ingress-worker-tick")

    override suspend fun postStart(ctx: ActorContext<WorkerCommand>) {
        ctx.timers.once(tickTimerKey, ZERO, WorkerCommand.Tick)
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
                ctx.timers.once(tickTimerKey, if (processed) ZERO else config.workerPollInterval, WorkerCommand.Tick)
            }
        }
    }
}

/**
 * Drives one pipeline step execution loop.
 *
 * Like [IngressWorkerActor], this actor advances itself through timer events and does not expose a
 * user-command protocol. `capacity = 0` makes that no-user-mailbox design explicit.
 */
private class StepWorkerActor(
    private val runtime: PipelineRuntime,
    private val config: RuntimeConfig,
    private val stepName: String,
) : Actor<WorkerCommand>(capacity = 0) {
    private val tickTimerKey = TimerKey("step-worker-tick")

    override suspend fun postStart(ctx: ActorContext<WorkerCommand>) {
        ctx.timers.once(tickTimerKey, ZERO, WorkerCommand.Tick)
    }

    override suspend fun handle(
        ctx: ActorContext<WorkerCommand>,
        command: WorkerCommand,
    ) {
        when (command) {
            WorkerCommand.Tick -> {
                val processed = runtime.executeStep(stepName = stepName, workerId = ctx.label)
                ctx.timers.once(tickTimerKey, if (processed) ZERO else config.workerPollInterval, WorkerCommand.Tick)
            }
        }
    }
}

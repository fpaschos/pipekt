package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.ReplyChannel
import io.github.fpaschos.pipekt.actor.Request
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext

internal sealed interface RuntimeCommand {
    class Snapshot(
        replyTo: ReplyChannel<PipelineSnapshot>,
    ) : Request<PipelineSnapshot>(replyTo),
        RuntimeCommand
}

internal class PipelineRuntimeActor(
    private val pipeline: PipelineDefinition,
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
    private val planVersion: String,
    private val config: RuntimeConfig,
) : Actor<RuntimeCommand>() {
    private lateinit var actorScope: CoroutineScope

    private lateinit var engine: PipelineExecutionEngine
    private lateinit var runId: String
    private var lifecycle: PipelineLifecycle = PipelineLifecycle.SHUTDOWN

    override suspend fun postStart(ctx: ActorContext<RuntimeCommand>) {
        actorScope =
            CoroutineScope(
                currentCoroutineContext() +
                    SupervisorJob(currentCoroutineContext()[kotlinx.coroutines.Job]) +
                    CoroutineName("${ctx.label}/runtime"),
            )
        engine =
            PipelineExecutionEngine(
                pipeline = pipeline,
                store = store,
                serializer = serializer,
                scope = actorScope,
                planVersion = planVersion,
                config = config,
            )
        val run = engine.start()
        runId = run.id
        lifecycle = PipelineLifecycle.RUNNING
    }

    override suspend fun handle(
        ctx: ActorContext<RuntimeCommand>,
        command: RuntimeCommand,
    ) {
        when (command) {
            is RuntimeCommand.Snapshot ->
                command.success(
                    PipelineSnapshot(
                        pipelineName = pipeline.name,
                        planVersion = planVersion,
                        runId = runId,
                        lifecycle = lifecycle,
                    ),
                )
        }
    }

    override suspend fun preStop(ctx: ActorContext<RuntimeCommand>) {
        if (::engine.isInitialized) {
            engine.stop()
        }
        if (::actorScope.isInitialized) {
            actorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        lifecycle = PipelineLifecycle.SHUTDOWN
    }

    override suspend fun postStop(ctx: ActorContext<RuntimeCommand>) {
        lifecycle = PipelineLifecycle.SHUTDOWN
    }
}

package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.actor.Actor
import io.github.fpaschos.pipekt.actor.ActorContext
import io.github.fpaschos.pipekt.actor.InternalActorContext
import io.github.fpaschos.pipekt.actor.ReplyChannel
import io.github.fpaschos.pipekt.actor.Request
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore

internal sealed interface RuntimeCommand {
    class Snapshot(
        replyTo: ReplyChannel<PipelineSnapshot>,
    ) : Request<PipelineSnapshot>(replyTo),
        RuntimeCommand
}

internal class PipelineRuntimeActor(
    ctx: ActorContext<RuntimeCommand>,
    private val pipeline: PipelineDefinition,
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
    private val planVersion: String,
    private val config: RuntimeConfig,
) : Actor<RuntimeCommand>(ctx) {
    private val actorScope = (ctx as InternalActorContext<RuntimeCommand>).scope

    private lateinit var engine: PipelineExecutionEngine
    private lateinit var runId: String
    private var lifecycle: PipelineLifecycle = PipelineLifecycle.SHUTDOWN

    override suspend fun postStart() {
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

    override suspend fun handle(command: RuntimeCommand) {
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

    override suspend fun preStop() {
        if (::engine.isInitialized) {
            engine.stop()
        }
        lifecycle = PipelineLifecycle.SHUTDOWN
    }

    override suspend fun postStop() {
        lifecycle = PipelineLifecycle.SHUTDOWN
    }
}

package io.github.fpaschos.pipekt.runtime.new

import io.github.fpaschos.pipekt.core.PipelineDefinition

/**
 * DSL start helper:
 *
 * `with(orchestrator) { definition.start(planVersion = "v1") }`
 */
context(orchestrator: PipelineOrchestrator)
suspend fun PipelineDefinition.start(
    planVersion: String,
    config: RuntimeConfig = RuntimeConfig(),
): PipelineExecutable =
    orchestrator.startPipeline(
        definition = this,
        planVersion = planVersion,
        config = config,
    )

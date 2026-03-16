package io.github.fpaschos.pipekt.runtime

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
): RuntimeHandle =
    orchestrator.startPipeline(
        definition = this,
        planVersion = planVersion,
        config = config,
    )

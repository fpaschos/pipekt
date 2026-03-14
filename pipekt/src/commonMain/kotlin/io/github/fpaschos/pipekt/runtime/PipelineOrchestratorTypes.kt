package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * Config for the store-level watchdog owned by [PipelineOrchestrator].
 */
data class OrchestratorConfig(
    val watchdogInterval: Duration = RuntimeConfig().watchdogInterval,
    val reclaimLimit: Int = 100,
)

/**
 * Public per-pipeline control handle.
 *
 * The handle is intentionally narrow: it controls a single pipeline runtime and does not expose
 * orchestrator internals.
 */
interface PipelineHandle {
    val pipelineName: String

    /** Stops this pipeline runtime (idempotent). */
    suspend fun stop()

    /** Returns a runtime snapshot for this pipeline. */
    suspend fun snapshot(): PipelineRuntimeV2Snapshot
}

/**
 * Factory hook for constructing runtime instances managed by [PipelineOrchestrator].
 */
fun interface PipelineRuntimeV2Factory {
    fun create(
        definition: PipelineDefinition,
        planVersion: String,
        dependencies: PipelineRuntimeV2Dependencies,
    ): PipelineRuntimeV2
}

/**
 * Runtime dependencies provided by orchestrator to [PipelineRuntimeV2Factory].
 */
data class PipelineRuntimeV2Dependencies(
    val store: DurableStore,
    val serializer: PayloadSerializer,
    val scope: CoroutineScope,
)

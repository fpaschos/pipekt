package io.github.fpaschos.pipekt.runtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Config for the store-level watchdog owned by [PipelineOrchestrator].
 */
data class OrchestratorConfig(
    val watchdogInterval: Duration = 50.milliseconds,
    val reclaimLimit: Int = 100,
)

/**
 * Public handle for controlling a started pipeline runtime.
 *
 * Runtime internals stay encapsulated; callers interact only through this handle.
 */
class RuntimeHandle internal constructor(
    val id: String,
    val pipelineName: String,
    val planVersion: String,
    val runId: String,
    private val controller: RuntimeHandleController,
) {
    suspend fun stop() = controller.stopHandle(id)

    suspend fun snapshot(): PipelineSnapshot = controller.snapshotHandle(id)
}

internal interface RuntimeHandleController {
    suspend fun stopHandle(handleId: String)

    suspend fun snapshotHandle(handleId: String): PipelineSnapshot
}

/**
 * Tunable parameters for a pipeline run.
 * Immutable; use the same instance for the lifetime of a run.
 *
 * See `plans/streams-technical-requirements.md` for recommended ranges in production.
 */
data class RuntimeConfig(
    val workerPollInterval: Duration = 10.milliseconds,
    val leaseDuration: Duration = 30.seconds,
    val workerClaimLimit: Int = 10,
)

/**
 * Public runtime lifecycle snapshot.
 */
data class PipelineSnapshot(
    val pipelineName: String,
    val planVersion: String,
    val runId: String,
    val lifecycle: PipelineLifecycle,
)

/** Lifecycle states for a running pipeline runtime. */
enum class PipelineLifecycle {
    RUNNING,
    SHUTDOWN,
}

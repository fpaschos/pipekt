package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.store.DurableStore
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Config for the store-level watchdog owned by [PipelineOrchestrator].
 */
data class OrchestratorConfig(
    val watchdogInterval: Duration = RuntimeConfig().watchdogInterval,
    val reclaimLimit: Int = 100,
)

/**
 * Public handle for controlling a started pipeline runtime.
 *
 * Runtime internals stay encapsulated; callers interact only through this handle.
 */
@ConsistentCopyVisibility
data class RuntimeRef internal constructor(
    val id: String,
    val name: String,
    private val runtime: PipelineRuntimeV2,
) {
    suspend fun shutdown() = runtime.shutdown()
}

/**
 * Runtime dependencies.
 */
internal data class RuntimeDeps(
    val store: DurableStore,
    val serializer: PayloadSerializer,
    val scope: CoroutineScope,
)

/**
 * Tunable parameters for a pipeline run. Passed to [PipelineRuntime.start].
 * Immutable; use the same instance for the lifetime of a run.
 *
 * See `plans/streams-technical-requirements.md` for recommended ranges in production.
 */
data class RuntimeConfig(
    val workerPollInterval: Duration = 10.milliseconds,
    val watchdogInterval: Duration = 50.milliseconds,
    val leaseDuration: Duration = 30.seconds,
    val workerClaimLimit: Int = 10,
)

/**
 * Public runtime lifecycle snapshot.
 */
data class PipelineSnapshot(
    val pipelineName: String,
    val planVersion: String,
    val lifecycle: PipelineLifecycle,
)

/** Lifecycle states for [PipelineRuntimeV2]. */
enum class PipelineLifecycle {
    NEW,
    RUNNING,
    SHUTDOWN,
}

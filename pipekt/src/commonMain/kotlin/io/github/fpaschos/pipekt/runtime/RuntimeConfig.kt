package io.github.fpaschos.pipekt.runtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

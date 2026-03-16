package io.github.fpaschos.pipekt.runtime.new

import kotlin.time.Duration

/**
 * User-facing capability for one started in-memory pipeline runtime.
 */
interface PipelineExecutable {
    val executableId: String
    val pipelineName: String
    val planVersion: String
    val runId: String

    suspend fun stop(timeout: Duration? = null)

    suspend fun snapshot(): PipelineExecutableSnapshot?
}

data class PipelineExecutableSnapshot(
    val executableId: String,
    val pipelineName: String,
    val planVersion: String,
    val runId: String,
)

class PipelineAlreadyActiveException(
    pipelineName: String,
    planVersion: String,
) : IllegalStateException("Pipeline $pipelineName is already active for plan version $planVersion.")

class PipelineExecutableNotFoundException(
    executableId: String,
) : IllegalStateException("No active pipeline executable exists for id $executableId.")

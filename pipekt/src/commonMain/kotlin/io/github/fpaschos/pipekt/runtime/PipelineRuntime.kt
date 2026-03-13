package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.store.RunRecord

/**
 * Entry-point contract for pipeline execution.
 */
interface PipelineRuntime {
    /**
     * Creates a new run, ingests [records] from the source adapter, and
     * executes all pipeline steps for each record.
     *
     * Returns the [RunRecord] created for this execution.
     */
    suspend fun startRun(records: List<IngressRecord<*>>): RunRecord

    /**
     * Resumes any non-terminal runs by re-queuing pending work items and
     * continuing step execution from where they left off.
     */
    suspend fun resumeRuns()

    /**
     * Ingests [record] into an existing run identified by [runId] and
     * executes pipeline steps for it.
     */
    suspend fun ingestAndExecute(
        runId: String,
        record: IngressRecord<*>,
    )
}

package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord

/**
 * Durable store SPI for pipeline runs and work items.
 *
 * All checkpoint operations are atomic (single transaction in Postgres).
 * See plans/streams-contracts-v1.md and streams-delivery-additions.md (Addition 5).
 */
interface DurableStore {
    suspend fun getOrCreateRun(
        pipeline: String,
        planVersion: String,
        nowMs: Long,
    ): RunRecord

    suspend fun getRun(runId: String): RunRecord?

    suspend fun listActiveRuns(pipeline: String): List<RunRecord>

    suspend fun appendIngress(
        runId: String,
        records: List<IngressRecord<*>>,
        nowMs: Long,
    ): AppendIngressResult

    suspend fun claim(
        step: String,
        runId: String,
        limit: Int,
        leaseMs: Long,
        workerId: String,
    ): List<WorkItem>

    suspend fun checkpointSuccess(
        item: WorkItem,
        outputJson: String,
        nextStep: String?,
        nowMs: Long,
    )

    suspend fun checkpointFiltered(
        item: WorkItem,
        reason: String,
        nowMs: Long,
    )

    suspend fun checkpointFailure(
        item: WorkItem,
        errorJson: String,
        retryAtEpochMs: Long?,
        nowMs: Long,
    )

    suspend fun countNonTerminal(runId: String): Int

    suspend fun reclaimExpiredLeases(
        nowEpochMs: Long,
        limit: Int,
    ): List<WorkItem>
}

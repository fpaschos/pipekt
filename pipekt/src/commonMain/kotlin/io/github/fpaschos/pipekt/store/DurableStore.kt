package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord

/**
 * Durable store SPI for pipeline runs and work items.
 *
 * Implementations persist runs, ingested records, and work items so that pipeline execution
 * can be resumed after failures. All checkpoint operations are atomic (e.g. single transaction
 * in Postgres).
 *
 * See plans/streams-contracts-v1.md and streams-delivery-additions.md (Addition 5).
 */
interface DurableStore {

    /**
     * Returns the existing active run for the pipeline and plan version, or creates a new run.
     *
     * @param pipeline Pipeline name.
     * @param planVersion Version of the pipeline plan (for compatibility and schema evolution).
     * @param nowMs Current time in epoch milliseconds (used for [RunRecord.createdAtMs]/updatedAtMs).
     * @return The run record; newly created or existing matching run.
     */
    suspend fun getOrCreateRun(
        pipeline: String,
        planVersion: String,
        nowMs: Long,
    ): RunRecord

    /**
     * Loads a run by id.
     *
     * @param runId Unique run identifier.
     * @return The run record, or null if not found.
     */
    suspend fun getRun(runId: String): RunRecord?

    /**
     * Lists runs for the pipeline that are considered active (e.g. not terminal).
     *
     * @param pipeline Pipeline name.
     * @return List of active run records, in an implementation-defined order.
     */
    suspend fun listActiveRuns(pipeline: String): List<RunRecord>

    /**
     * Appends ingested records to a run; duplicates for the same (runId, sourceId) are skipped.
     *
     * @param runId Run to append to.
     * @param records Ingress records to append (payload type is erased).
     * @param nowMs Current time in epoch milliseconds.
     * @return [AppendIngressResult] with counts of appended and duplicate records.
     */
    suspend fun appendIngress(
        runId: String,
        records: List<IngressRecord<*>>,
        nowMs: Long,
    ): AppendIngressResult

    /**
     * Claims up to [limit] work items for the given step and run, under a lease.
     *
     * Only items that are unclaimed or whose lease has expired are eligible. Claimed items
     * are associated with [workerId] and lease expiry [leaseMs] from now.
     *
     * @param step Step name to claim for.
     * @param runId Run id.
     * @param limit Maximum number of items to claim.
     * @param leaseMs Lease duration in milliseconds from now.
     * @param workerId Identifier of the worker claiming the items.
     * @return List of claimed [WorkItem]s (at most [limit]).
     */
    suspend fun claim(
        step: String,
        runId: String,
        limit: Int,
        leaseMs: Long,
        workerId: String,
    ): List<WorkItem>

    /**
     * Marks the work item as completed successfully and advances it to the next step or terminal.
     *
     * Atomic with the store's transaction model.
     *
     * @param item The work item to checkpoint.
     * @param outputJson Serialized output payload for the next step (or terminal marker).
     * @param nextStep Next step name, or null if the item is terminal.
     * @param nowMs Current time in epoch milliseconds.
     */
    suspend fun checkpointSuccess(
        item: WorkItem,
        outputJson: String,
        nextStep: String?,
        nowMs: Long,
    )

    /**
     * Marks the work item as filtered out (e.g. dropped by a filter step).
     *
     * @param item The work item to checkpoint.
     * @param reason Human- or machine-readable reason for filtering.
     * @param nowMs Current time in epoch milliseconds.
     */
    suspend fun checkpointFiltered(
        item: WorkItem,
        reason: String,
        nowMs: Long,
    )

    /**
     * Marks the work item as failed; it may be retried later if [retryAtEpochMs] is set.
     *
     * @param item The work item to checkpoint.
     * @param errorJson Serialized error information.
     * @param retryAtEpochMs Epoch ms when the item may be retried, or null if no retry.
     * @param nowMs Current time in epoch milliseconds.
     */
    suspend fun checkpointFailure(
        item: WorkItem,
        errorJson: String,
        retryAtEpochMs: Long?,
        nowMs: Long,
    )

    /**
     * Returns the number of work items in the run that are not in a terminal status.
     *
     * @param runId Run id.
     * @return Count of non-terminal work items.
     */
    suspend fun countNonTerminal(runId: String): Int

    /**
     * Reclaims work items whose lease has expired by the given time.
     *
     * @param nowEpochMs Current time in epoch milliseconds.
     * @param limit Maximum number of items to reclaim.
     * @return List of reclaimed [WorkItem]s (at most [limit]).
     */
    suspend fun reclaimExpiredLeases(
        nowEpochMs: Long,
        limit: Int,
    ): List<WorkItem>
}

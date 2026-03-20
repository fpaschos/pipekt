package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Durable store SPI for pipeline runs and work items.
 *
 * Implementations persist runs, ingested records, and work items so that pipeline execution
 * can be resumed after failures. All checkpoint operations are atomic (e.g. single transaction
 * in Postgres).
 *
 * Timestamp fields (`createdAt`, `updatedAt`, lease expiry) are owned by the store implementation;
 * callers do not supply `now`. The only exception is [reclaimExpiredLeases], where the caller
 * provides the cutoff instant so the runtime can control when expiry is evaluated.
 *
 * See `specs/streams-contracts-v1.md` (Store Contracts) and `specs/streams-delivery-phases.md` (Phase 1B).
 */
interface DurableStore {
    /**
     * Returns the existing active run for the pipeline and plan version, or creates a new run.
     *
     * Keyed by `(pipeline, planVersion)`. Bumping [planVersion] creates a fresh run while older
     * runs remain in the store. Timestamps are set by the store implementation.
     *
     * @param pipeline Pipeline name.
     * @param planVersion Version of the pipeline plan (for compatibility and schema evolution).
     * @return The run record
     */
    suspend fun findOrCreateRun(
        pipeline: String,
        planVersion: String,
    ): RunRecord

    /**
     * Loads a run by id.
     *
     * @param runId Unique run identifier.
     * @return The run record, or null if not found.
     */
    suspend fun findRun(runId: String): RunRecord?

    /**
     * Lists runs for the pipeline that are considered active (e.g. not terminal).
     *
     * @param pipeline Pipeline name.
     * @return List of active run records, in an implementation-defined order.
     */
    suspend fun findAllActiveRuns(pipeline: String): List<RunRecord>

    /**
     * Appends ingested records to a run; duplicates for the same `(runId, sourceId)` are skipped.
     *
     * Uses `ON CONFLICT (run_id, source_id) DO NOTHING` semantics. Timestamps on newly created
     * [WorkItem]s are set by the store implementation.
     *
     * @param runId Run to append to.
     * @param records Ingress records to append (payload is serialized and stored).
     * @param firstStep Name of the first operator step to assign as [WorkItem.currentStep]; use empty string if not applicable.
     * @return [AppendIngressResult] with counts of appended and duplicate records.
     */
    suspend fun appendIngress(
        runId: String,
        records: List<IngressRecord<*>>,
        firstStep: String = "",
    ): AppendIngressResult

    /**
     * Claims up to [limit] work items for the given step and run, under a lease.
     *
     * Only items that are unclaimed or whose lease has expired are eligible. Claimed items
     * are associated with [workerId] and a lease expiry of `(store's now) + leaseDuration`.
     * Uses `SELECT ... FOR UPDATE SKIP LOCKED` semantics.
     *
     * @param step Step name to claim for.
     * @param runId Run id.
     * @param limit Maximum number of items to claim.
     * @param leaseDuration Lease duration from the store's current time.
     * @param workerId Identifier of the worker claiming the items.
     * @return List of claimed [WorkItem]s (at most [limit]).
     */
    suspend fun claim(
        step: String,
        runId: String,
        limit: Int,
        leaseDuration: Duration,
        workerId: String,
    ): List<WorkItem>

    /**
     * Marks the work item as completed successfully and advances it to the next step or terminal.
     *
     * Atomic: increments `attemptCount`, updates `currentStep` and `status`, nulls `payloadJson`
     * for terminal items, and sets `updatedAt` — all in one transaction.
     *
     * @param item The work item to checkpoint.
     * @param outputJson Serialized output payload for the next step (or terminal marker).
     * @param nextStep Next step name, or null if the item has reached a terminal state.
     */
    suspend fun checkpointSuccess(
        item: WorkItem,
        outputJson: String,
        nextStep: String?,
    )

    /**
     * Marks the work item as filtered out (e.g. dropped by a filter step).
     *
     * Atomic: increments `attemptCount`, sets status to [WorkItemStatus.FILTERED], nulls
     * `payloadJson`, and sets `updatedAt` — all in one transaction.
     *
     * @param item The work item to checkpoint.
     * @param reason Human- or machine-readable reason for filtering.
     */
    suspend fun checkpointFiltered(
        item: WorkItem,
        reason: String,
    )

    /**
     * Marks the work item as failed; it may be retried later if [retryAt] is set.
     *
     * Atomic: increments `attemptCount`, sets `lastErrorJson`, optionally sets `retryAt`,
     * updates `status`, and sets `updatedAt` — all in one transaction.
     *
     * @param item The work item to checkpoint.
     * @param errorJson Serialized error information.
     * @param retryAt Instant when the item may be retried, or null if no retry is scheduled.
     *   This is computed by the caller (runtime) based on the retry policy backoff.
     */
    suspend fun checkpointFailure(
        item: WorkItem,
        errorJson: String,
        retryAt: Instant?,
    )

    /**
     * Returns the number of work items in the run that are not in a terminal status.
     *
     * Counts items with status `PENDING` or `IN_PROGRESS`. Used by the ingestion loop to
     * enforce `maxInFlight` backpressure.
     *
     * @param runId Run id.
     * @return Count of non-terminal work items.
     */
    suspend fun countNonTerminal(runId: String): Int

    /**
     * Reclaims work items whose lease has expired by [now].
     *
     * Resets `IN_PROGRESS` items whose [WorkItem.leaseExpiry] is before [now] back to `PENDING`,
     * clearing the lease owner and expiry. The caller (runtime watchdog) supplies [now] so that
     * expiry evaluation is deterministic and testable.
     *
     * @param now Cutoff instant; items with [WorkItem.leaseExpiry] before this are reclaimed.
     * @param limit Maximum number of items to reclaim in one call.
     * @return List of reclaimed [WorkItem]s (at most [limit]).
     */
    suspend fun reclaimExpiredLeases(
        now: Instant,
        limit: Int,
    ): List<WorkItem>
}

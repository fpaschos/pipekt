package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.AppendIngressResult
import io.github.fpaschos.pipekt.core.BarrierResult
import io.github.fpaschos.pipekt.core.FinalizerStartResult
import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.ItemFailure
import io.github.fpaschos.pipekt.core.RunStatus

/**
 * Durable persistence contract for the pipeline runtime.
 *
 * All methods are suspend to allow both in-memory (coroutine-safe) and
 * IO-backed implementations. Implementations must be safe for concurrent
 * callers but do not need to guarantee cross-process atomicity beyond
 * what is described per method.
 */
interface DurableStore {
    // ── Run lifecycle ─────────────────────────────────────────────────────────

    /** Creates a new [RunRecord] with status [RunStatus.PENDING] and returns it. */
    suspend fun createRun(
        pipelineName: String,
        nowMs: Long,
    ): RunRecord

    /** Returns the [RunRecord] for [runId], or null if not found. */
    suspend fun getRun(runId: String): RunRecord?

    /** Updates the [RunStatus] of [runId], recording [nowMs] as the update timestamp. */
    suspend fun updateRunStatus(
        runId: String,
        status: RunStatus,
        nowMs: Long,
    )

    /** Returns all runs for [pipelineName] that are not in a terminal state. */
    suspend fun listActiveRuns(pipelineName: String): List<RunRecord>

    // ── Ingress / work-item lifecycle ─────────────────────────────────────────

    /**
     * Attempts to create a [WorkItem] for the given [record].
     *
     * Returns [AppendIngressResult.DUPLICATE] if a work item with the same
     * [IngressRecord.sourceId] already exists for [runId] (idempotent ingress).
     */
    suspend fun appendIngress(
        runId: String,
        record: IngressRecord<*>,
        payloadJson: String,
        stepName: String,
        nowMs: Long,
    ): AppendIngressResult

    /**
     * Claims up to [max] [WorkItem]s in [WorkItemStatus.PENDING] state for [stepName]
     * within [runId], atomically transitioning them to [WorkItemStatus.IN_PROGRESS].
     */
    suspend fun claimPendingItems(
        runId: String,
        stepName: String,
        max: Int = 10,
    ): List<WorkItem>

    /** Returns all [WorkItem]s for [runId] at [stepName] regardless of status. */
    suspend fun getItemsForStep(
        runId: String,
        stepName: String,
    ): List<WorkItem>

    /** Updates [workItemId]'s step, status, and payload after a successful step execution. */
    suspend fun checkpointItem(
        workItemId: String,
        nextStep: String,
        status: WorkItemStatus,
        payloadJson: String,
        nowMs: Long,
    )

    // ── Attempt records ───────────────────────────────────────────────────────

    /** Records a step execution attempt for audit and retry tracking. */
    suspend fun recordAttempt(attempt: AttemptRecord)

    /** Returns all [AttemptRecord]s for [workItemId] ordered by [AttemptRecord.attemptNumber]. */
    suspend fun getAttempts(workItemId: String): List<AttemptRecord>

    // ── Barrier ───────────────────────────────────────────────────────────────

    /**
     * Checks whether all work items in [runId] that were assigned to [predecessorStep]
     * have reached a terminal state.
     *
     * Returns [BarrierResult.READY] when the condition is satisfied,
     * [BarrierResult.WAITING] otherwise.
     */
    suspend fun evaluateBarrier(
        runId: String,
        predecessorStep: String,
    ): BarrierResult

    // ── Finalizer ─────────────────────────────────────────────────────────────

    /**
     * Attempts to acquire the finalizer lock for [runId].
     *
     * Returns [FinalizerStartResult.STARTED] exactly once per [runId];
     * subsequent calls return [FinalizerStartResult.ALREADY_STARTED].
     */
    suspend fun tryStartFinalizer(
        runId: String,
        nowMs: Long,
    ): FinalizerStartResult

    /** Marks the finalizer for [runId] as complete. */
    suspend fun completeFinalizer(
        runId: String,
        nowMs: Long,
    )

    // ── Failure handling ──────────────────────────────────────────────────────

    /**
     * Records a [failure] for [workItemId] at [stepName] and transitions the
     * work item to [WorkItemStatus.FAILED].
     */
    suspend fun failItem(
        workItemId: String,
        stepName: String,
        failure: ItemFailure,
        nowMs: Long,
    )
}

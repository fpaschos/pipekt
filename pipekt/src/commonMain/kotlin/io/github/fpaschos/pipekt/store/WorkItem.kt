package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.WorkItemStatus
import kotlin.time.Instant

/**
 * Persistent work item for a single ingested record in a run.
 *
 * Represents one record's progress through the pipeline.
 * Instances are immutable value types produced by the store.
 *
 * @param id Unique work item id.
 * @param runId of the run this item belongs to.
 * @param sourceId Source-level identifier of the ingested record.
 * @param currentStep Step name the item is currently at (or was last checkpointed for).
 * @param status Current [WorkItemStatus] of the item. Terminal statuses are [WorkItemStatus.COMPLETED],
 *   [WorkItemStatus.FILTERED], and [WorkItemStatus.FAILED]
 * @param payloadJson Serialized payload for the current step. For efficiency is null at terminal states.
 * @param attemptCount Number of processing attempts for the current step.
 * @param leaseOwner id of the worker holding the lease, or null if unclaimed.
 * @param leaseExpiry Instant when the lease expires, or null if unclaimed.
 * @param retryAt Instant when a failed item may be retried.
 * @param createdAt creation time.
 * @param updatedAt last update time.
 */
data class WorkItem(
    val id: String,
    val runId: String,
    val sourceId: String,
    val currentStep: String,
    val status: WorkItemStatus,
    val payloadJson: String?,
    val attemptCount: Int,
    val leaseOwner: String?,
    val leaseExpiry: Instant?,
    val retryAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

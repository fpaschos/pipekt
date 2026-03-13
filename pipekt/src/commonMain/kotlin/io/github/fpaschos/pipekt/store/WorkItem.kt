package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.WorkItemStatus

/**
 * Persistent work item for a single ingested record in a run.
 *
 * Represents one record's progress through the pipeline. Required fields per streams-contracts-v1.
 * Instances are immutable value types produced by the store.
 *
 * @param id Unique work item id.
 * @param runId Id of the run this item belongs to.
 * @param sourceId Source-level identifier of the ingested record.
 * @param currentStep Step name the item is currently at (or was last checkpointed for).
 * @param status Current [WorkItemStatus] (e.g. PENDING, IN_PROGRESS, SUCCESS, FAILED).
 * @param payloadJson Serialized payload for the current step; null at terminal checkpoint.
 * @param lastErrorJson Serialized last error, if any (e.g. after checkpointFailure).
 * @param attemptCount Number of processing attempts for the current step.
 * @param leaseOwner Id of the worker holding the lease, or null if unclaimed.
 * @param leaseExpiryMs Epoch ms when the lease expires, or null if unclaimed.
 * @param retryAtMs Epoch ms when a failed item may be retried, or null.
 * @param createdAtMs Epoch ms when the item was created.
 * @param updatedAtMs Epoch ms when the item was last updated.
 */
data class WorkItem(
    val id: String,
    val runId: String,
    val sourceId: String,
    val currentStep: String,
    val status: WorkItemStatus,
    val payloadJson: String?,
    val lastErrorJson: String?,
    val attemptCount: Int,
    val leaseOwner: String?,
    val leaseExpiryMs: Long?,
    val retryAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.WorkItemStatus

/**
 * Persistent work item for a single ingested record in a run.
 *
 * Required fields per streams-contracts-v1: item id, run id, source id, current step,
 * status, nullable payloadJson (nulled at terminal checkpoint), lastErrorJson, attemptCount,
 * leaseOwner, leaseExpiryMs, retryAtMs. Timestamps added for alignment with Phase 3 schema.
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

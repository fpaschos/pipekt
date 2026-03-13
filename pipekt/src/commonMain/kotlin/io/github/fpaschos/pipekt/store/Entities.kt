package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.ItemFailure
import io.github.fpaschos.pipekt.core.RunStatus

// ── Run record ────────────────────────────────────────────────────────────────

/**
 * Represents a single execution run of a pipeline triggered by one source batch.
 */
data class RunRecord(
    val id: String,
    val pipelineName: String,
    val status: RunStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

// ── Work item ─────────────────────────────────────────────────────────────────

/**
 * Represents one unit of work (a single ingested record) within a [RunRecord].
 * [currentStep] tracks which operator the item is currently being processed by.
 * [payloadJson] holds the serialized form of the item's current value.
 */
data class WorkItem(
    val id: String,
    val runId: String,
    val sourceId: String,
    val currentStep: String,
    val status: WorkItemStatus,
    val payloadJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

enum class WorkItemStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FILTERED,
    FAILED,
}

// ── Attempt record ────────────────────────────────────────────────────────────

/**
 * Records each execution attempt of a [WorkItem] at a specific step.
 */
data class AttemptRecord(
    val id: String,
    val workItemId: String,
    val runId: String,
    val stepName: String,
    val attemptNumber: Int,
    val outcome: AttemptOutcome,
    val failure: ItemFailure?,
    val startedAtMs: Long,
    val finishedAtMs: Long,
)

enum class AttemptOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    FATAL_FAILURE,
    FILTERED,
}

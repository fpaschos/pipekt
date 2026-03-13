package gr.pipekt.streams.core

import arrow.core.raise.Raise
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// ── Step execution context ────────────────────────────────────────────────────

data class StepCtx(
    val pipelineName: String,
    val runId: String,
    val itemId: String,
    val itemKey: String,
    val stepName: String,
    val attempt: Int,
    val startedAt: Long,
    val metadata: Map<String, String> = emptyMap(),
)

// ── Step function typealias ───────────────────────────────────────────────────

typealias StepFn<I, O, E> = suspend context(Raise<E>, StepCtx)
(I) -> O

// ── Source / Ingress records ──────────────────────────────────────────────────

data class SourceRecord<T>(
    val id: String,
    val payload: T,
)

@OptIn(ExperimentalUuidApi::class)
data class IngressRecord<T>(
    val id: Uuid = Uuid.random(),
    val sourceId: String,
    val payload: T,
)

// ── Retry policy ─────────────────────────────────────────────────────────────

data class RetryPolicy(
    val maxAttempts: Int,
    val backoffMs: Long = 0L,
)

// ── Failure hierarchy ─────────────────────────────────────────────────────────

enum class FilteredReason { BELOW_THRESHOLD, DUPLICATE, EXCLUDED }

sealed class ItemFailure {
    data class Filtered(val reason: FilteredReason) : ItemFailure()
    data class Retryable(val cause: String, val attemptNumber: Int) : ItemFailure()
    data class Fatal(val cause: String) : ItemFailure()
    data class InfrastructureFailure(val cause: String) : ItemFailure()
}

// ── Work item status ──────────────────────────────────────────────────────────

enum class WorkItemStatus { PENDING, IN_PROGRESS, COMPLETED, FILTERED, FAILED }

// ── Store operation results ───────────────────────────────────────────────────

enum class AppendIngressResult { APPENDED, DUPLICATE }

package io.github.fpaschos.pipekt.core

import arrow.core.raise.Raise
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// ── Step execution context ────────────────────────────────────────────────────

data class StepCtx(
    val runId: String,
    val stepName: String,
    val attemptNumber: Int,
)

// ── Step function typealias ───────────────────────────────────────────────────

typealias StepFn<I, O, E> = context(Raise<E>, StepCtx)
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
    data class Filtered(
        val reason: FilteredReason,
    ) : ItemFailure()

    data class Retryable(
        val cause: String,
        val attemptNumber: Int,
    ) : ItemFailure()

    data class Fatal(
        val cause: String,
    ) : ItemFailure()

    data class InfrastructureFailure(
        val cause: String,
    ) : ItemFailure()
}

// ── Step output ───────────────────────────────────────────────────────────────

sealed class StepOutput<out O> {
    data class Value<O>(
        val value: O,
    ) : StepOutput<O>()

    data class Failure(
        val failure: ItemFailure,
    ) : StepOutput<Nothing>()
}

// ── Run status ────────────────────────────────────────────────────────────────

enum class RunStatus {
    PENDING,
    IN_PROGRESS,
    AWAITING_BARRIER,
    FINALIZED,
    FAILED,
}

// ── Store operation results ───────────────────────────────────────────────────

enum class AppendIngressResult { APPENDED, DUPLICATE }

enum class BarrierResult { READY, WAITING }

enum class FinalizerStartResult { STARTED, ALREADY_STARTED }

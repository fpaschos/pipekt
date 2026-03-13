package io.github.fpaschos.pipekt.core

import arrow.core.raise.Raise
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// ── Step execution context ────────────────────────────────────────────────────

/**
 * Execution context passed into every step function at runtime.
 *
 * The runtime supplies this when invoking [StepFn]; step implementations use it for
 * logging, idempotency keys, and metadata propagation. All fields are read-only.
 *
 * @param pipelineName Name of the pipeline this item belongs to.
 * @param runId Unique identifier for this pipeline run (used for checkpointing and backpressure).
 * @param itemId Unique identifier for this work item (e.g. UUID).
 * @param itemKey Stable key from the source (e.g. source record id); used for idempotent ingress.
 * @param stepName Name of the step currently executing.
 * @param attempt Current attempt number (1-based); used with [RetryPolicy].
 * @param startedAt Epoch millis when this attempt started.
 * @param metadata Optional key-value metadata; can be extended by operators.
 */
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

/**
 * Step function contract: a suspend function that runs in a [Raise] context for [ItemFailure],
 * receives [StepCtx] and input [I], and returns [O].
 *
 * The error channel is fixed to [ItemFailure] — there is no generic error type parameter.
 * [ItemFailure] is an open interface; steps raise any value that implements it, including
 * custom domain types (e.g. `raise(GatewayTimeout(503))`). The [Raise]<[ItemFailure]> context
 * accepts subtypes, so no wrapping is needed for the common case.
 *
 * For callers who need to map typed domain errors to a specific [ItemFailure] subtype before the
 * runtime routes them, Arrow's `withError` can be used at the step boundary.
 *
 * Implementations must be multiplatform-safe and should avoid blocking.
 *
 * @param I Input type for this step (payload or previous step output).
 * @param O Output type (passed to the next operator or terminal).
 */
typealias StepFn<I, O> = suspend context(Raise<ItemFailure>, StepCtx)
(I) -> O

// ── Source / Ingress records ──────────────────────────────────────────────────

/**
 * A single record produced by a [SourceAdapter] when polling.
 *
 * The engine uses [id] as the stable [StepCtx.itemKey] for idempotent ingestion and checkpointing.
 *
 * @param id Stable identifier from the source (e.g. message id); must be unique per source.
 * @param payload The typed payload for this record.
 */
data class SourceRecord<T>(
    val id: String,
    val payload: T,
)

/**
 * Internal record after ingestion: adds an engine-generated [id] and carries [sourceId] for ack/nack.
 *
 * Default [id] is a new [Uuid] per record; the store uses (runId, sourceId) for deduplication.
 *
 * @param id Unique id for this ingested item (default: [Uuid.random]).
 * @param sourceId Same as [SourceRecord.id]; used when calling [SourceAdapter.ack] or [SourceAdapter.nack].
 * @param payload The typed payload.
 */
@OptIn(ExperimentalUuidApi::class)
data class IngressRecord<T>(
    val id: Uuid = Uuid.random(),
    val sourceId: String,
    val payload: T,
)

// ── Retry policy ─────────────────────────────────────────────────────────────

/**
 * Retry policy for a step: how many attempts and optional delay between attempts.
 *
 * Used by [StepDef]; the runtime applies this when a step raises a retryable error
 * (e.g. [ItemFailure.Retryable]). Backoff is applied after each failed attempt before the next.
 *
 * @param maxAttempts Maximum number of attempts (including the first); must be at least 1.
 * @param backoffMs Delay in milliseconds between attempts; 0 means no delay.
 */
data class RetryPolicy(
    val maxAttempts: Int,
    val backoffMs: Long = 0L,
)

// ── Failure hierarchy ─────────────────────────────────────────────────────────

/**
 * Reason for filtering an item out of the pipeline (used with [CoreFailure.Filtered]).
 *
 * - [BELOW_THRESHOLD]: Item did not meet a business threshold (e.g. score).
 * - [DUPLICATE]: Item was identified as duplicate and excluded.
 * - [EXCLUDED]: Generic exclusion (e.g. business rule).
 */
enum class FilteredReason { BELOW_THRESHOLD, DUPLICATE, EXCLUDED }

/**
 * Open interface for all step failure outcomes in the pipeline.
 *
 * Implement this interface on your own domain error types to integrate with the runtime's
 * routing logic without wrapping. The runtime dispatches on [isRetryable] and [isFiltered]
 * to decide whether to retry, filter, or permanently fail an item.
 *
 * The library-provided sealed hierarchy [CoreFailure] covers the common cases. Custom domain
 * errors should implement this interface and override the routing methods as needed.
 *
 * Example:
 * ```kotlin
 * data class GatewayTimeout(val code: Int) : ItemFailure {
 *     override val message = "Gateway timeout: $code"
 *     override fun isRetryable() = true
 * }
 * ```
 */
interface ItemFailure {
    /** Human-readable description of the failure; used for logging and [WorkItem.lastErrorJson]. */
    val message: String

    /**
     * Returns true if the runtime should schedule another attempt according to the step's [RetryPolicy].
     * Defaults to false (fatal — no retry).
     */
    fun isRetryable(): Boolean = false

    /**
     * Returns true if the item should be marked [WorkItemStatus.FILTERED] rather than [WorkItemStatus.FAILED].
     * Defaults to false.
     */
    fun isFiltered(): Boolean = false
}

/**
 * Library-owned sealed hierarchy of common failure outcomes; all implement [ItemFailure].
 *
 * Use these in steps via the Arrow [Raise] DSL (`raise(CoreFailure.Fatal("reason"))`).
 * For domain-specific errors with custom retry logic, implement [ItemFailure] directly instead.
 */
sealed class CoreFailure : ItemFailure {
    /**
     * Permanent business failure; no retry. Item is checkpointed as FAILED.
     * @param message Human-readable cause.
     */
    data class Fatal(
        override val message: String,
    ) : CoreFailure()

    /**
     * Transient failure; runtime retries according to the step's [RetryPolicy].
     * @param message Human-readable cause (e.g. for logging).
     * @param attempt The attempt number that failed (1-based).
     */
    data class Retryable(
        override val message: String,
        val attempt: Int,
    ) : CoreFailure() {
        override fun isRetryable() = true
    }

    /**
     * Item was filtered out (e.g. by a predicate). Checkpointed as FILTERED, not FAILED.
     * @param reason Why the item was filtered; see [FilteredReason].
     */
    data class Filtered(
        val reason: FilteredReason,
    ) : CoreFailure() {
        override val message: String get() = reason.name

        override fun isFiltered() = true
    }

    /**
     * Infrastructure or system failure (e.g. timeout, connection error).
     * Treated as fatal by default; override [isRetryable] on a custom type for retryable infra errors.
     * @param message Human-readable cause.
     */
    data class InfrastructureFailure(
        override val message: String,
    ) : CoreFailure()
}

// ── Work item status ──────────────────────────────────────────────────────────

/**
 * Status of a work item in the store.
 *
 * - [PENDING]: Not yet claimed by a worker.
 * - [IN_PROGRESS]: Claimed; worker is executing the step.
 * - [COMPLETED]: Step completed successfully; item is terminal.
 * - [FILTERED]: Item was filtered out; terminal.
 * - [FAILED]: Step failed (fatal or max retries); terminal.
 */
enum class WorkItemStatus { PENDING, IN_PROGRESS, COMPLETED, FILTERED, FAILED }

// ── Store operation results ───────────────────────────────────────────────────

/**
 * Result of appending an ingress record to the store.
 *
 * - [APPENDED]: Record was inserted.
 * - [DUPLICATE]: A record with the same (runId, sourceId) already existed; idempotent no-op.
 */
enum class AppendIngressResult { APPENDED, DUPLICATE }

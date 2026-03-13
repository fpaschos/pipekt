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
 * Step function contract: a suspend function that runs in a [Raise] context for error type [E],
 * receives [StepCtx] and input [I], and returns [O].
 *
 * Steps can raise [E] for business or retryable errors; the runtime uses this for checkpointing
 * and retry policy. Implementations must be multiplatform-safe and should avoid blocking.
 *
 * @param I Input type for this step (payload or previous step output).
 * @param O Output type (passed to the next operator or terminal).
 * @param E Error type that can be raised (e.g. [ItemFailure] or a domain type).
 */
typealias StepFn<I, O, E> = suspend context(Raise<E>, StepCtx)
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
 * Reason for filtering an item out of the pipeline (used with [ItemFailure.Filtered]).
 *
 * - [BELOW_THRESHOLD]: Item did not meet a business threshold (e.g. score).
 * - [DUPLICATE]: Item was identified as duplicate and excluded.
 * - [EXCLUDED]: Generic exclusion (e.g. business rule).
 */
enum class FilteredReason { BELOW_THRESHOLD, DUPLICATE, EXCLUDED }

/**
 * Sealed hierarchy of item outcomes: filtered, retryable failure, fatal failure, or infrastructure failure.
 *
 * Steps raise these (or domain types) via the [Raise] context; the runtime uses them for
 * checkpointing and retry/backoff behavior.
 */
sealed class ItemFailure {
    /**
     * Item was filtered out (e.g. by a [FilterDef] predicate).
     * @param reason Why the item was filtered; see [FilteredReason].
     */
    data class Filtered(
        val reason: FilteredReason,
    ) : ItemFailure()

    /**
     * Transient failure; runtime may retry according to [RetryPolicy].
     * @param cause Human-readable cause (e.g. for logging).
     * @param attemptNumber The attempt that failed (1-based).
     */
    data class Retryable(
        val cause: String,
        val attemptNumber: Int,
    ) : ItemFailure()

    /**
     * Permanent business failure; no retry. Item is checkpointed as FAILED.
     * @param cause Human-readable cause.
     */
    data class Fatal(
        val cause: String,
    ) : ItemFailure()

    /**
     * Infrastructure or system failure (e.g. timeout, connection); may be retried by policy.
     * @param cause Human-readable cause.
     */
    data class InfrastructureFailure(
        val cause: String,
    ) : ItemFailure()
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

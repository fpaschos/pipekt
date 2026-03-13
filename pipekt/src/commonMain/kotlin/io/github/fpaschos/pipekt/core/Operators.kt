package io.github.fpaschos.pipekt.core

/**
 * Base type for all pipeline operators: source, steps, filter, and persist-each.
 *
 * Every operator has a [name] that must be unique within a pipeline (including the source name).
 * Used in [PipelineDefinition.operators] and for validation.
 */
sealed class OperatorDef {
    /** Unique name for this operator within the pipeline; used in [StepCtx.stepName] and validation. */
    abstract val name: String
}

/**
 * Source operator: defines the single ingress point for a pipeline.
 *
 * There is exactly one [SourceDef] per pipeline, set via [PipelineBuilder.source].
 * The [adapter] is the engine-level contract for polling and ack/nack.
 *
 * @param name Unique name for the source step (e.g. "ingress").
 * @param adapter Implementation that polls records and acknowledges or nacks them.
 */
data class SourceDef<T>(
    override val name: String,
    val adapter: SourceAdapter<T>,
) : OperatorDef()

/**
 * Transform step: applies [fn] to each item with [RetryPolicy] for retries.
 *
 * [fn] receives the current payload (or previous step output) and [StepCtx]; it can raise [E]
 * for retryable or fatal errors. The runtime invokes it in a [Raise] context.
 *
 * @param name Unique step name.
 * @param retryPolicy How many attempts and backoff; default is single attempt.
 * @param fn The step function; see [StepFn].
 */
data class StepDef<I, O, E>(
    override val name: String,
    val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
    val fn: StepFn<I, O, E>,
) : OperatorDef()

/**
 * Filter step: keeps or drops items based on [predicate]; dropped items are checkpointed as filtered.
 *
 * [predicate] returns true to keep the item, false to filter it out. Filtered items use
 * [filteredReason] for observability (e.g. [FilteredReason.DUPLICATE]).
 *
 * @param name Unique step name.
 * @param filteredReason Reason used when the item is filtered; default [FilteredReason.BELOW_THRESHOLD].
 * @param predicate Step function returning true to keep, false to filter out.
 */
data class FilterDef<T, E>(
    override val name: String,
    val filteredReason: FilteredReason = FilteredReason.BELOW_THRESHOLD,
    val predicate: StepFn<T, Boolean, E>,
) : OperatorDef()

/**
 * Persist-each boundary: marks a point after which each item is durably checkpointed before
 * proceeding to the next step. No function; the runtime handles persistence.
 *
 * @param name Unique step name for this boundary.
 */
data class PersistEachDef(
    override val name: String,
) : OperatorDef()

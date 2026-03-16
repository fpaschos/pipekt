package io.github.fpaschos.pipekt.core

import kotlin.reflect.KType

/**
 * Base type for all pipeline operators: source, steps, and filter.
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
 * [fn] receives the current payload (or previous step output) and [StepCtx]; it raises
 * [ItemFailure] (or any subtype) for retryable, filtered, or fatal errors. The runtime
 * invokes it in a [Raise]<[ItemFailure]> context.
 *
 * [inputType] and [outputType] are captured at DSL call sites via `inline reified` and are used
 * for type-chain validation at pipeline build time and for payload serialization at runtime.
 *
 * @param name Unique step name.
 * @param retryPolicy How many attempts and backoff; default is single attempt.
 * @param fn The step function; see [StepFn].
 * @param inputType The [KType] of the input payload [I]; captured via `typeOf<I>()` at the call site.
 * @param outputType The [KType] of the output payload [O]; captured via `typeOf<O>()` at the call site.
 */
data class StepDef<I, O>(
    override val name: String,
    val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
    val fn: StepFn<I, O>,
    val inputType: KType,
    val outputType: KType,
) : OperatorDef()

/**
 * Filter step: keeps or drops items based on [predicate]; dropped items are checkpointed as filtered.
 *
 * [predicate] returns true to keep the item, false to filter it out. Filtered items use
 * [filteredReason] for observability (e.g. [FilteredReason.DUPLICATE]).
 *
 * [inputType] is captured at the DSL call site via `inline reified` and is used for type-chain
 * validation. The output type of a filter is always the same as its input type (items pass through
 * unchanged when kept).
 *
 * @param name Unique step name.
 * @param filteredReason Reason used when the item is filtered; default [FilteredReason.BELOW_THRESHOLD].
 * @param predicate Step function returning true to keep, false to filter out; raises [ItemFailure].
 * @param inputType The [KType] of the input payload [T]; captured via `typeOf<T>()` at the call site.
 */
data class FilterDef<T>(
    override val name: String,
    val filteredReason: FilteredReason = FilteredReason.BELOW_THRESHOLD,
    val predicate: StepFn<T, Boolean>,
    val inputType: KType,
) : OperatorDef()

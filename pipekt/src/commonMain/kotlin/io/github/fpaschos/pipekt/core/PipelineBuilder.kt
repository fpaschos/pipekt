package io.github.fpaschos.pipekt.core

import arrow.core.Either
import kotlin.reflect.KType
import kotlin.reflect.typeOf

// ── DSL builder ───────────────────────────────────────────────────────────────

/**
 * Builder for constructing a pipeline via the DSL; the receiver of the [pipeline] block.
 *
 * Each operator call ([source], [step], [filter]) is an independent statement
 * on this receiver — no chaining is required. Type [T] is the source payload type, fixed at
 * the [pipeline] call site.
 *
 * Inter-step type compatibility is validated at [build] time via [validate]; the runtime [KType]
 * of each step's input is read from [currentOutputType], which is advanced by each [step] call.
 * This means a single reified type param on [step] (`O2` — the output) is sufficient; no explicit
 * input type annotation is needed at the call site.
 *
 * @param T Source payload type.
 * @param name Pipeline name.
 * @param maxInFlight Maximum in-flight items per run; must be positive.
 * @param retentionDays Archival cutoff in days; default 30.
 * @param sourceType [KType] of [T]; captured via `typeOf<T>()` at the [pipeline] call site and
 *   used as the starting point for type-chain validation in [validate].
 */
class PipelineBuilder<T>(
    private val name: String,
    private val maxInFlight: Int,
    private val retentionDays: Int = 30,
    @PublishedApi internal val sourceType: KType,
) {
    private var source: SourceDef<T>? = null

    @PublishedApi internal val operators = mutableListOf<OperatorDef>()

    // Tracks the KType flowing out of the most recently added operator (or source).
    // Advanced by step(); read by step() and filter() to populate inputType on the operator def.
    @PublishedApi internal var currentOutputType: KType = sourceType

    /**
     * Sets the single source for this pipeline.
     *
     * @param name Unique name for the source step.
     * @param adapter The [SourceAdapter] implementation for polling and ack/nack.
     */
    fun source(
        name: String,
        adapter: SourceAdapter<T>,
    ) {
        source = SourceDef(name = name, adapter = adapter)
    }

    /**
     * Appends a transform step.
     *
     * Both [I] (input) and [O] (output) are reified and inferred from [fn] at the call site.
     * When the lambda input type can be determined from the lambda parameter, no explicit type
     * annotation is needed at the call site (e.g. `{ it: String -> it.length }` pins `I = String`
     * and the compiler infers `O = Int`; for same-type steps `{ it: String -> it }` pins both).
     *
     * [inputType] is captured via `typeOf<I>()` and [outputType] via `typeOf<O>()` for payload
     * serialization at runtime. [currentOutputType] is advanced to `typeOf<O>()` for the next operator.
     *
     * The error channel is fixed to [ItemFailure] — steps raise any [ItemFailure] subtype via
     * the [arrow.core.raise.Raise]<[ItemFailure]> context.
     *
     * @param name Unique step name.
     * @param retryPolicy Retry policy for this step; default single attempt.
     * @param fn The step function; specify the input type as the lambda parameter type so that
     *   both [I] and [O] can be resolved.
     */
    inline fun <reified I, reified O> step(
        name: String,
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
        noinline fn: StepFn<I, O>,
    ) {
        @Suppress("UNCHECKED_CAST")
        operators +=
            StepDef(
                name = name,
                retryPolicy = retryPolicy,
                fn = fn as StepFn<Any?, Any?>,
                inputType = typeOf<I>(),
                outputType = typeOf<O>(),
            )
        currentOutputType = typeOf<O>()
    }

    /**
     * Appends a filter step; items for which [predicate] returns false are filtered out.
     *
     * [I] is reified and inferred from [predicate] at the call site. [FilterDef.inputType] is
     * captured via `typeOf<I>()` for type-chain validation and serialization. The output type
     * is unchanged (filter is transparent). The error channel is fixed to [ItemFailure].
     *
     * @param name Unique step name.
     * @param filteredReason Reason when filtered; default [FilteredReason.BELOW_THRESHOLD].
     * @param predicate Returns true to keep, false to filter out.
     */
    inline fun <reified I> filter(
        name: String,
        filteredReason: FilteredReason = FilteredReason.BELOW_THRESHOLD,
        noinline predicate: StepFn<I, Boolean>,
    ) {
        @Suppress("UNCHECKED_CAST")
        operators +=
            FilterDef(
                name = name,
                filteredReason = filteredReason,
                predicate = predicate as StepFn<Any?, Boolean>,
                inputType = typeOf<I>(),
            )
    }

    /**
     * Validates and builds the pipeline; equivalent to calling [validate] with the builder's
     * current state.
     *
     * @return [Either.Right] with [PipelineDefinition] on success, [Either.Left] with a non-empty
     *   list of [PipelineValidationError] on failure.
     */
    fun build(): Either<List<PipelineValidationError>, PipelineDefinition> =
        validate(
            name = name,
            source = source,
            sourceType = sourceType,
            operators = operators,
            maxInFlight = maxInFlight,
            retentionDays = retentionDays,
        )
}

/**
 * Top-level DSL entry to define a pipeline.
 *
 * Creates a [PipelineBuilder]<[T]> as the block receiver. Each call inside the block
 * ([PipelineBuilder.source], [PipelineBuilder.step], [PipelineBuilder.filter]) is a standalone statement — no chaining is required and no
 * explicit input/output type annotations are needed on [PipelineBuilder.step] or
 * [PipelineBuilder.filter].
 *
 * `T` is reified so that `typeOf<T>()` can be captured for type-chain validation and payload
 * serialization. `T` can be inferred from the adapter passed to [PipelineBuilder.source].
 *
 * @param T Source payload type.
 * @param name Pipeline name.
 * @param maxInFlight Maximum in-flight items per run (must be positive).
 * @param retentionDays Archival cutoff in days; default 30.
 * @param block Configuration block; must call [PipelineBuilder.source] and add at least one operator.
 * @return [Either.Right] with [PipelineDefinition] on success, [Either.Left] with [PipelineValidationError] list on failure.
 */
inline fun <reified T> pipeline(
    name: String,
    maxInFlight: Int,
    retentionDays: Int = 30,
    block: PipelineBuilder<T>.() -> Unit,
): Either<List<PipelineValidationError>, PipelineDefinition> =
    PipelineBuilder<T>(
        name = name,
        maxInFlight = maxInFlight,
        retentionDays = retentionDays,
        sourceType = typeOf<T>(),
    ).apply(block).build()

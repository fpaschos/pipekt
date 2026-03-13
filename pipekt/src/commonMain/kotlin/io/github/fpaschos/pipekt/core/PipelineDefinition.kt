package io.github.fpaschos.pipekt.core

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.reflect.KType
import kotlin.reflect.typeOf

// ── Pipeline definition ───────────────────────────────────────────────────────

/**
 * Validated pipeline descriptor used by the runtime to execute a pipeline.
 *
 * Built via [pipeline] DSL or [validate]; all validation (duplicate names, missing source,
 * empty operators, invalid maxInFlight, type-chain consistency) must pass. The runtime uses
 * [maxInFlight] for backpressure and [retentionDays] for archival of terminal work items.
 *
 * @param name Pipeline name (unique per runtime instance).
 * @param source The single source operator; see [SourceDef].
 * @param operators Ordered list of steps, filters, and persist-each boundaries.
 * @param maxInFlight Maximum number of non-terminal items per run before ingestion pauses.
 * @param retentionDays Days after which terminal items can be archived; default 30.
 */
data class PipelineDefinition(
    val name: String,
    val source: SourceDef<*>,
    val operators: List<OperatorDef>,
    val maxInFlight: Int,
    val retentionDays: Int = 30,
)

// ── Validation errors ─────────────────────────────────────────────────────────

/**
 * Sealed union of validation failures returned by [validate] and [PipelineBuilder.build].
 *
 * Multiple errors can be collected in a single pass (e.g. duplicate names, type mismatches,
 * and invalid maxInFlight are all reported together).
 */
sealed class PipelineValidationError {
    /** At least two operators (or source) share the same [name]; names must be unique. */
    data class DuplicateStepName(
        val name: String,
    ) : PipelineValidationError()

    /** No source was defined via [PipelineBuilder.source]. */
    data object NoSourceDefined : PipelineValidationError()

    /** [operators] is empty; at least one step, filter, or persistEach is required. */
    data object EmptyPipeline : PipelineValidationError()

    /** [maxInFlight] is not positive; must be >= 1 for backpressure. */
    data object InvalidMaxInFlight : PipelineValidationError()

    /**
     * The input type of [stepName] does not match the output type of the previous operator.
     *
     * @param stepName Name of the operator whose input type does not match.
     * @param expected The [KType] the operator declares as its input (from [StepDef.inputType] or [FilterDef.inputType]).
     * @param actual The [KType] produced by the preceding operator (or source).
     */
    data class TypeMismatch(
        val stepName: String,
        val expected: KType,
        val actual: KType,
    ) : PipelineValidationError()
}

// ── Validation logic ──────────────────────────────────────────────────────────

/**
 * Validates pipeline parameters and either builds a [PipelineDefinition] or collects all errors.
 *
 * Checks (all collected, not short-circuited):
 * - Source present.
 * - Non-empty operators.
 * - maxInFlight > 0.
 * - No duplicate names (source name and all operator names must be unique).
 * - Type chain: each operator's declared input type must match the output type of the preceding
 *   operator (or the source type for the first operator). [PersistEachDef] is transparent.
 *
 * Returns [Either.Right] with the definition only when there are no errors.
 *
 * @param name Pipeline name.
 * @param source The source definition, or null if not set.
 * @param sourceType The [KType] of the source payload; used as the starting type for chain validation.
 * @param operators Ordered list of operator definitions.
 * @param maxInFlight Must be positive.
 * @param retentionDays Default 30; used for archival.
 * @return [Either.Right] with [PipelineDefinition] on success, [Either.Left] with non-empty list of [PipelineValidationError] on failure.
 */
fun validate(
    name: String,
    source: SourceDef<*>?,
    sourceType: KType,
    operators: List<OperatorDef>,
    maxInFlight: Int,
    retentionDays: Int = 30,
): Either<List<PipelineValidationError>, PipelineDefinition> {
    val errors = mutableListOf<PipelineValidationError>()

    if (source == null) errors += PipelineValidationError.NoSourceDefined
    if (operators.isEmpty()) errors += PipelineValidationError.EmptyPipeline
    if (maxInFlight <= 0) errors += PipelineValidationError.InvalidMaxInFlight

    val allNames =
        buildList {
            source?.let { add(it.name) }
            addAll(operators.map { it.name })
        }
    allNames
        .groupBy { it }
        .filter { it.value.size > 1 }
        .keys
        .forEach { errors += PipelineValidationError.DuplicateStepName(it) }

    // Type-chain validation: walk operators tracking the current output type.
    // Only runs when a source is present (otherwise NoSourceDefined is already collected).
    if (source != null) {
        var currentType = sourceType
        for (op in operators) {
            when (op) {
                is StepDef<*, *> -> {
                    if (op.inputType != currentType) {
                        errors +=
                            PipelineValidationError.TypeMismatch(
                                stepName = op.name,
                                expected = op.inputType,
                                actual = currentType,
                            )
                    }
                    currentType = op.outputType
                }
                is FilterDef<*> -> {
                    if (op.inputType != currentType) {
                        errors +=
                            PipelineValidationError.TypeMismatch(
                                stepName = op.name,
                                expected = op.inputType,
                                actual = currentType,
                            )
                    }
                    // filter output type is same as input — currentType unchanged
                }
                is PersistEachDef -> {
                    // transparent pass-through; type unchanged
                }
                is SourceDef<*> -> {
                    // source is not in the operators list; no-op
                }
            }
        }
    }

    return if (errors.isEmpty() && source != null) {
        PipelineDefinition(
            name = name,
            source = source,
            operators = operators,
            maxInFlight = maxInFlight,
            retentionDays = retentionDays,
        ).right()
    } else {
        errors.left()
    }
}

// ── DSL builder ───────────────────────────────────────────────────────────────

/**
 * Builder for constructing a pipeline via the DSL (source, step, filter, persistEach).
 *
 * Use the top-level [pipeline] function to create a builder, configure it in the block, then
 * [build] returns the same result as [validate]. Type [T] is the source payload type.
 *
 * @param sourceType The [KType] of the source payload [T]; captured via `typeOf<T>()` at the
 *   [pipeline] call site. Used as the starting type for type-chain validation.
 */
class PipelineBuilder<T>(
    private val name: String,
    private val maxInFlight: Int,
    private val retentionDays: Int = 30,
    @PublishedApi internal val sourceType: KType,
) {
    private var source: SourceDef<T>? = null

    @PublishedApi internal val operators = mutableListOf<OperatorDef>()

    // Tracks the output type of the most recently added operator; starts as sourceType.
    // Updated by step() and filter() so each subsequent call can read the current flowing type.
    @PublishedApi internal var currentOutputType: KType = sourceType

    /**
     * Sets the single source for this pipeline.
     * @param name Unique name for the source step.
     * @param adapter The [SourceAdapter] implementation for polling and ack/nack.
     * @return this builder for chaining.
     */
    fun source(
        name: String,
        adapter: SourceAdapter<T>,
    ): PipelineBuilder<T> {
        source = SourceDef(name = name, adapter = adapter)
        return this
    }

    /**
     * Appends a transform step.
     *
     * [I] and [O] are captured as reified types at the call site. The error channel is fixed to
     * [ItemFailure] — no error type parameter is required. Steps raise any [ItemFailure] subtype
     * directly via the [Raise]<[ItemFailure]> context receiver.
     * The step's input type is validated against the preceding operator's output type at [build] time.
     *
     * @param name Unique step name.
     * @param retryPolicy Retry policy for this step; default single attempt.
     * @param fn The step function.
     * @return this builder for chaining.
     */
    inline fun <reified I, reified O> step(
        name: String,
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
        noinline fn: StepFn<I, O>,
    ): PipelineBuilder<T> {
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
        return this
    }

    /**
     * Appends a filter step; items for which [predicate] returns false are filtered out.
     *
     * [I] is the input type flowing into the filter; it must match the output type of the
     * preceding operator (validated at [build] time). Filter output type equals input type —
     * items pass through unchanged when kept. The error channel is fixed to [ItemFailure].
     *
     * @param name Unique step name.
     * @param filteredReason Reason when filtered; default [FilteredReason.BELOW_THRESHOLD].
     * @param predicate Returns true to keep, false to filter.
     * @return this builder for chaining.
     */
    inline fun <reified I> filter(
        name: String,
        filteredReason: FilteredReason = FilteredReason.BELOW_THRESHOLD,
        noinline predicate: StepFn<I, Boolean>,
    ): PipelineBuilder<T> {
        @Suppress("UNCHECKED_CAST")
        operators +=
            FilterDef(
                name = name,
                filteredReason = filteredReason,
                predicate = predicate as StepFn<Any?, Boolean>,
                inputType = typeOf<I>(),
            )
        // filter is transparent — currentOutputType unchanged
        return this
    }

    /**
     * Appends a persist-each boundary (durable checkpoint before next step).
     * @param name Unique step name.
     * @return this builder for chaining.
     */
    fun persistEach(name: String): PipelineBuilder<T> {
        operators += PersistEachDef(name = name)
        return this
    }

    /**
     * Validates and builds the pipeline; equivalent to [validate] with the builder's current state.
     * @return [Either.Right] with [PipelineDefinition] on success, [Either.Left] with [PipelineValidationError] list on failure.
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
 * Top-level DSL entry to define a pipeline: creates a [PipelineBuilder], runs [block] to configure
 * source and operators, then returns the result of [build] (validated [PipelineDefinition] or errors).
 *
 * `T` is reified so that `typeOf<T>()` can be captured and threaded into the builder for type-chain
 * validation and payload serialization.
 *
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

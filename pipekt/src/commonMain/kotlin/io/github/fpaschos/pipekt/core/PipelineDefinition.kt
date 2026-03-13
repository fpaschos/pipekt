package io.github.fpaschos.pipekt.core

import arrow.core.Either
import arrow.core.left
import arrow.core.right

// ── Pipeline definition ───────────────────────────────────────────────────────

/**
 * Validated pipeline descriptor used by the runtime to execute a pipeline.
 *
 * Built via [pipeline] DSL or [validate]; all validation (duplicate names, missing source,
 * empty operators, invalid maxInFlight) must pass. The runtime uses [maxInFlight] for
 * backpressure and [retentionDays] for archival of terminal work items.
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
 * Multiple errors can be collected (e.g. duplicate names and invalid maxInFlight together).
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
}

// ── Validation logic ──────────────────────────────────────────────────────────

/**
 * Validates pipeline parameters and either builds a [PipelineDefinition] or collects all errors.
 *
 * Checks: source present, non-empty operators, maxInFlight > 0, and no duplicate names
 * (source name and all operator names must be unique). Returns [Either.Right] with the
 * definition only when there are no errors.
 *
 * @param name Pipeline name.
 * @param source The source definition, or null if not set.
 * @param operators Ordered list of operator definitions.
 * @param maxInFlight Must be positive.
 * @param retentionDays Default 30; used for archival.
 * @return [Either.Right] with [PipelineDefinition] on success, [Either.Left] with non-empty list of [PipelineValidationError] on failure.
 */
fun validate(
    name: String,
    source: SourceDef<*>?,
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
 */
class PipelineBuilder<T>(
    private val name: String,
    private val maxInFlight: Int,
    private val retentionDays: Int = 30,
) {
    private var source: SourceDef<T>? = null
    private val operators = mutableListOf<OperatorDef>()

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
     * @param name Unique step name.
     * @param retryPolicy Retry policy for this step; default single attempt.
     * @param fn The step function.
     * @return this builder for chaining.
     */
    fun <O, E> step(
        name: String,
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
        fn: StepFn<T, O, E>,
    ): PipelineBuilder<T> {
        @Suppress("UNCHECKED_CAST")
        operators += StepDef(name = name, retryPolicy = retryPolicy, fn = fn as StepFn<Any?, Any?, Any?>)
        return this
    }

    /**
     * Appends a filter step; items for which [predicate] returns false are filtered out.
     * @param name Unique step name.
     * @param filteredReason Reason when filtered; default [FilteredReason.BELOW_THRESHOLD].
     * @param predicate Returns true to keep, false to filter.
     * @return this builder for chaining.
     */
    fun <E> filter(
        name: String,
        filteredReason: FilteredReason = FilteredReason.BELOW_THRESHOLD,
        predicate: StepFn<T, Boolean, E>,
    ): PipelineBuilder<T> {
        @Suppress("UNCHECKED_CAST")
        operators +=
            FilterDef(
                name = name,
                filteredReason = filteredReason,
                predicate = predicate as StepFn<Any?, Boolean, Any?>,
            )
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
            operators = operators,
            maxInFlight = maxInFlight,
            retentionDays = retentionDays,
        )
}

/**
 * Top-level DSL entry to define a pipeline: creates a [PipelineBuilder], runs [block] to configure
 * source and operators, then returns the result of [build] (validated [PipelineDefinition] or errors).
 *
 * @param name Pipeline name.
 * @param maxInFlight Maximum in-flight items per run (must be positive).
 * @param retentionDays Archival cutoff in days; default 30.
 * @param block Configuration block; must call [PipelineBuilder.source] and add at least one operator.
 * @return [Either.Right] with [PipelineDefinition] on success, [Either.Left] with [PipelineValidationError] list on failure.
 */
fun <T> pipeline(
    name: String,
    maxInFlight: Int,
    retentionDays: Int = 30,
    block: PipelineBuilder<T>.() -> Unit,
): Either<List<PipelineValidationError>, PipelineDefinition> =
    PipelineBuilder<T>(name = name, maxInFlight = maxInFlight, retentionDays = retentionDays)
        .apply(block)
        .build()

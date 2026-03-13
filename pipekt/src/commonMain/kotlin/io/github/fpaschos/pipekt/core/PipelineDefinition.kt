package io.github.fpaschos.pipekt.core

import arrow.core.Either
import arrow.core.left
import arrow.core.right

// ── Pipeline definition ───────────────────────────────────────────────────────

data class PipelineDefinition(
    val name: String,
    val source: SourceDef<*>,
    val operators: List<OperatorDef>,
    val maxInFlight: Int,
    val retentionDays: Int = 30,
)

// ── Validation errors ─────────────────────────────────────────────────────────

sealed class PipelineValidationError {
    data class DuplicateStepName(
        val name: String,
    ) : PipelineValidationError()

    data object NoSourceDefined : PipelineValidationError()

    data object EmptyPipeline : PipelineValidationError()

    data object InvalidMaxInFlight : PipelineValidationError()
}

// ── Validation logic ──────────────────────────────────────────────────────────

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

class PipelineBuilder<T>(
    private val name: String,
    private val maxInFlight: Int,
    private val retentionDays: Int = 30,
) {
    private var source: SourceDef<T>? = null
    private val operators = mutableListOf<OperatorDef>()

    fun source(
        name: String,
        adapter: SourceAdapter<T>,
    ): PipelineBuilder<T> {
        source = SourceDef(name = name, adapter = adapter)
        return this
    }

    fun <O, E> step(
        name: String,
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
        fn: StepFn<T, O, E>,
    ): PipelineBuilder<T> {
        @Suppress("UNCHECKED_CAST")
        operators += StepDef(name = name, retryPolicy = retryPolicy, fn = fn as StepFn<Any?, Any?, Any?>)
        return this
    }

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

    fun persistEach(name: String): PipelineBuilder<T> {
        operators += PersistEachDef(name = name)
        return this
    }

    fun build(): Either<List<PipelineValidationError>, PipelineDefinition> =
        validate(
            name = name,
            source = source,
            operators = operators,
            maxInFlight = maxInFlight,
            retentionDays = retentionDays,
        )
}

fun <T> pipeline(
    name: String,
    maxInFlight: Int,
    retentionDays: Int = 30,
    block: PipelineBuilder<T>.() -> Unit,
): Either<List<PipelineValidationError>, PipelineDefinition> =
    PipelineBuilder<T>(name = name, maxInFlight = maxInFlight, retentionDays = retentionDays)
        .apply(block)
        .build()

package io.github.fpaschos.pipekt.core

import arrow.core.Either
import arrow.core.left
import arrow.core.right

// ── Pipeline definition ───────────────────────────────────────────────────────

data class PipelineDefinition(
    val name: String,
    val source: SourceDef<*>,
    val operators: List<OperatorDef>,
)

// ── Validation errors ─────────────────────────────────────────────────────────

sealed class PipelineValidationError {
    data class DuplicateStepName(
        val name: String,
    ) : PipelineValidationError()

    data class MultipleFinalizersFound(
        val names: List<String>,
    ) : PipelineValidationError()

    data class BarrierWithNoFinitePredecessor(
        val barrierName: String,
        val predecessorStep: String,
    ) : PipelineValidationError()

    data object NoSourceDefined : PipelineValidationError()

    data object EmptyPipeline : PipelineValidationError()
}

// ── Validation logic ──────────────────────────────────────────────────────────

fun validate(
    name: String,
    source: SourceDef<*>?,
    operators: List<OperatorDef>,
): Either<List<PipelineValidationError>, PipelineDefinition> {
    val errors = mutableListOf<PipelineValidationError>()

    if (source == null) errors += PipelineValidationError.NoSourceDefined
    if (operators.isEmpty()) errors += PipelineValidationError.EmptyPipeline

    val allNames = buildList {
        source?.let { add(it.name) }
        addAll(operators.map { it.name })
    }

    val duplicates = allNames.groupBy { it }.filter { it.value.size > 1 }.keys
    duplicates.forEach { errors += PipelineValidationError.DuplicateStepName(it) }

    val finalizers = operators.filterIsInstance<FinalizerDef<*, *>>()
    if (finalizers.size > 1) {
        errors += PipelineValidationError.MultipleFinalizersFound(finalizers.map { it.name })
    }

    val stepNames = operators.map { it.name }.toSet()
    operators.filterIsInstance<BarrierDef>().forEach { barrier ->
        if (barrier.predecessorStep !in stepNames) {
            errors += PipelineValidationError.BarrierWithNoFinitePredecessor(
                barrierName = barrier.name,
                predecessorStep = barrier.predecessorStep,
            )
        }
    }

    return if (errors.isEmpty() && source != null) {
        PipelineDefinition(name = name, source = source, operators = operators).right()
    } else {
        errors.left()
    }
}

// ── DSL builder ───────────────────────────────────────────────────────────────

class PipelineBuilder<T>(
    private val name: String,
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
                predicate = predicate as StepFn<Any?, Boolean, Any?>
            )
        return this
    }

    fun persistEach(name: String): PipelineBuilder<T> {
        operators += PersistEachDef(name = name)
        return this
    }

    fun barrier(
        name: String,
        predecessorStep: String,
    ): PipelineBuilder<T> {
        operators += BarrierDef(name = name, predecessorStep = predecessorStep)
        return this
    }

    fun <E> finalizer(
        name: String,
        fn: StepFn<List<T>, Unit, E>,
    ): PipelineBuilder<T> {
        @Suppress("UNCHECKED_CAST")
        operators += FinalizerDef(name = name, fn = fn as StepFn<List<Any?>, Unit, Any?>)
        return this
    }

    fun build(): Either<List<PipelineValidationError>, PipelineDefinition> =
        validate(name = name, source = source, operators = operators)
}

fun <T> pipeline(
    name: String,
    block: PipelineBuilder<T>.() -> Unit,
): Either<List<PipelineValidationError>, PipelineDefinition> = PipelineBuilder<T>(name).apply(block).build()

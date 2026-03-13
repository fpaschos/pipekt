package io.github.fpaschos.pipekt.core

import kotlin.reflect.KType

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

    /** operators contain no step or filter; at least one step or filter is required. */
    data object EmptyPipeline : PipelineValidationError()

    /** maxInFlight is not positive; must be >= 1 for backpressure. */
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

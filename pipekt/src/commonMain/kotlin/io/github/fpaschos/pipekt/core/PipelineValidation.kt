package io.github.fpaschos.pipekt.core

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.accumulate
import arrow.core.raise.either
import kotlin.reflect.KType

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
@OptIn(ExperimentalRaiseAccumulateApi::class)
fun validate(
    name: String,
    source: SourceDef<*>?,
    sourceType: KType,
    operators: List<OperatorDef>,
    maxInFlight: Int,
    retentionDays: Int = 30,
): Either<List<PipelineValidationError>, PipelineDefinition> =
    // either<NonEmptyList<...>> gives us Raise<NonEmptyList<E>>, on which accumulate{} is defined.
    // mapLeft converts NonEmptyList → List to match the public return type the callers expect.
    either<NonEmptyList<PipelineValidationError>, PipelineDefinition> {
        // accumulate{} runs the block as RaiseAccumulate<E>: raise() collects into the list
        // instead of short-circuiting, so every independent check is always evaluated.
        val resolvedSource =
            accumulate {
                ensureOrAccumulate(source != null) { PipelineValidationError.NoSourceDefined }
                ensureOrAccumulate(operators.isNotEmpty()) { PipelineValidationError.EmptyPipeline }
                ensureOrAccumulate(maxInFlight > 0) { PipelineValidationError.InvalidMaxInFlight }

                val allNames =
                    buildList {
                        source?.let { add(it.name) }
                        addAll(operators.map { it.name })
                    }
                allNames
                    .groupBy { it }
                    .filter { it.value.size > 1 }
                    .keys
                    .forEach { dupName -> accumulate(PipelineValidationError.DuplicateStepName(dupName)) }

                // Type-chain validation — only when source is present.
                if (source != null) {
                    var currentType = sourceType
                    for (op in operators) {
                        when (op) {
                            is StepDef<*, *> -> {
                                ensureOrAccumulate(op.inputType == currentType) {
                                    PipelineValidationError.TypeMismatch(
                                        stepName = op.name,
                                        expected = op.inputType,
                                        actual = currentType,
                                    )
                                }
                                currentType = op.outputType
                            }

                            is FilterDef<*> -> {
                                ensureOrAccumulate(op.inputType == currentType) {
                                    PipelineValidationError.TypeMismatch(
                                        stepName = op.name,
                                        expected = op.inputType,
                                        actual = currentType,
                                    )
                                }
                                // filter is transparent — currentType unchanged
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

                source
            }

        PipelineDefinition(
            name = name,
            source = resolvedSource!!,
            operators = operators,
            maxInFlight = maxInFlight,
            retentionDays = retentionDays,
        )
    }.mapLeft { it.toList() }

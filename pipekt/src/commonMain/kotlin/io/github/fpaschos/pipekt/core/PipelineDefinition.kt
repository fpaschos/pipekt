package io.github.fpaschos.pipekt.core

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.accumulate
import arrow.core.raise.either
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

    /** operators are empty; at least one step, filter, or persistEach is required. */
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

// ── DSL builder ───────────────────────────────────────────────────────────────

/**
 * Builder for constructing a pipeline via the DSL; the receiver of the [pipeline] block.
 *
 * Each operator call ([source], [step], [filter], [persistEach]) is an independent statement
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
        // filter is transparent — currentOutputType unchanged
    }

    /**
     * Appends a persist-each boundary (durable checkpoint before the next step).
     *
     * Type-transparent: [currentOutputType] is unchanged.
     *
     * @param name Unique step name.
     */
    fun persistEach(name: String) {
        operators += PersistEachDef(name = name)
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
 * ([PipelineBuilder.source], [PipelineBuilder.step], [PipelineBuilder.filter],
 * [PipelineBuilder.persistEach]) is a standalone statement — no chaining is required and no
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

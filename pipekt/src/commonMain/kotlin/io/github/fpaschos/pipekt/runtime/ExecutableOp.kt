package io.github.fpaschos.pipekt.runtime

import arrow.core.Either
import arrow.core.raise.Raise
import io.github.fpaschos.pipekt.core.FilterDef
import io.github.fpaschos.pipekt.core.ItemFailure
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.StepCtx
import io.github.fpaschos.pipekt.core.StepDef
import io.github.fpaschos.pipekt.store.DurableStore
import io.github.fpaschos.pipekt.store.WorkItem
import kotlin.reflect.KType

/**
 * Context provided by the runtime so that [ExecutableOp] implementations can run step functions,
 * checkpoint results, and handle failures without depending on [PipelineRuntime] directly.
 *
 * Implemented by the runtime when launching worker loops; allows [ExecutableOp] to live in a
 * separate file and remain testable.
 */
interface StepExecutorContext {
    val store: DurableStore
    val serializer: PayloadSerializer

    suspend fun <I, R> runStepFn(
        item: WorkItem,
        stepName: String,
        inputType: KType,
        block: suspend context(Raise<ItemFailure>, StepCtx) (I) -> R,
    ): Either<ItemFailure, R>

    suspend fun handleFailure(
        item: WorkItem,
        failure: ItemFailure,
        stepDef: StepDef<*, *>?,
    )
}

/**
 * Sealed abstraction for an executable pipeline operator (step or filter).
 *
 * Used by the runtime to launch one worker loop per operator. The when over operator defs
 * is done once when building the list of [ExecutableOp]s; the launch loop then iterates over
 * [ExecutableOp] with no further branching.
 *
 * Adding a new operator kind (e.g. MapDef) requires a new [ExecutableOp] subtype and
 * one extra branch in the exhaustive [when] that builds the list from [OperatorDef]s.
 */
sealed interface ExecutableOp {
    /** Step name used for [DurableStore.claim]. */
    val stepName: String

    /** Input [KType] for this operator; used by the ingestion loop for the first step's payload type. */
    val inputType: KType

    /**
     * Deserializes the item payload, executes the operator function, and checkpoints the result.
     *
     * @param ctx Runtime context for [runStepFn], [handleFailure], and store/serializer access.
     * @param item The claimed work item.
     * @param nextStep Next step name, or null if this is the final operator.
     */
    suspend fun executeAndCheckpoint(
        ctx: StepExecutorContext,
        item: WorkItem,
        nextStep: String?,
    )
}

/**
 * [ExecutableOp] for [StepDef]: runs the step function and checkpoints success (with serialized
 * output) or delegates failure to [StepExecutorContext.handleFailure].
 */
data class StepExecutable(
    private val def: StepDef<Any?, Any?>,
) : ExecutableOp {
    override val stepName: String get() = def.name
    override val inputType: KType get() = def.inputType

    override suspend fun executeAndCheckpoint(
        ctx: StepExecutorContext,
        item: WorkItem,
        nextStep: String?,
    ) {
        val result =
            ctx.runStepFn<Any?, Any?>(item, def.name, def.inputType) { input ->
                def.fn(input)
            }
        result.fold(
            ifLeft = { failure -> ctx.handleFailure(item, failure, def) },
            ifRight = { output ->
                requireNotNull(output) { "Step ${def.name} returned null; output must be non-null for checkpoint." }
                val outJson = ctx.serializer.serialize(output, def.outputType)
                ctx.store.checkpointSuccess(item, outJson, nextStep)
            },
        )
    }
}

/**
 * [ExecutableOp] for [FilterDef]: runs the filter predicate and checkpoints filtered (false
 * result or [ItemFailure.isFiltered]), success (true result, payload forwarded unchanged), or
 * delegates non-filter failures to [StepExecutorContext.handleFailure].
 */
data class FilterExecutable(
    private val def: FilterDef<Any?>,
) : ExecutableOp {
    override val stepName: String get() = def.name
    override val inputType: KType get() = def.inputType

    override suspend fun executeAndCheckpoint(
        ctx: StepExecutorContext,
        item: WorkItem,
        nextStep: String?,
    ) {
        val result =
            ctx.runStepFn<Any?, Boolean>(item, def.name, def.inputType) { input ->
                def.predicate(input)
            }
        result.fold(
            ifLeft = { failure ->
                if (failure.isFiltered()) {
                    ctx.store.checkpointFiltered(item, failure.message)
                } else {
                    ctx.handleFailure(item, failure, stepDef = null)
                }
            },
            ifRight = { keep ->
                if (keep) {
                    ctx.store.checkpointSuccess(item, item.payloadJson!!, nextStep)
                } else {
                    ctx.store.checkpointFiltered(item, def.filteredReason.name)
                }
            },
        )
    }
}

package io.github.fpaschos.pipekt.runtime.new

import io.github.fpaschos.pipekt.core.FilterDef
import io.github.fpaschos.pipekt.core.StepDef
import kotlin.reflect.KType

/**
 * Runtime-private compiled operator used by [PipelineRuntime].
 *
 * This keeps the runtime plan data-only: execution behavior remains in the engine and is selected
 * with an exhaustive `when` over the operator kind.
 */
internal sealed interface CompiledOperator {
    val stepName: String
    val inputType: KType
    val nextStepName: String?

    data class Step(
        val def: StepDef<Any?, Any?>,
        override val nextStepName: String?,
    ) : CompiledOperator {
        override val stepName: String = def.name
        override val inputType: KType = def.inputType
    }

    data class Filter(
        val def: FilterDef<Any?>,
        override val nextStepName: String?,
    ) : CompiledOperator {
        override val stepName: String = def.name
        override val inputType: KType = def.inputType
    }
}

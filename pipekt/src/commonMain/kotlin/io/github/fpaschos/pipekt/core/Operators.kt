package io.github.fpaschos.pipekt.core

sealed class OperatorDef {
    abstract val name: String
}

data class SourceDef<T>(
    override val name: String,
    val adapter: SourceAdapter<T>,
) : OperatorDef()

data class StepDef<I, O, E>(
    override val name: String,
    val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
    val fn: StepFn<I, O, E>,
) : OperatorDef()

data class FilterDef<T, E>(
    override val name: String,
    val filteredReason: FilteredReason = FilteredReason.BELOW_THRESHOLD,
    val predicate: StepFn<T, Boolean, E>,
) : OperatorDef()

data class PersistEachDef(
    override val name: String,
) : OperatorDef()

package io.github.fpaschos.pipekt.core

/**
 * Sealed hierarchy of pipeline operator definitions.
 * Each operator has a stable [name] used for checkpoint keys and validation.
 */
sealed class OperatorDef {
    abstract val name: String
}

/** Pulls records from an external source via [SourceAdapter]. */
data class SourceDef<T>(
    override val name: String,
    val adapter: SourceAdapter<T>,
) : OperatorDef()

/**
 * Applies a transformation [fn] to each work item.
 * [retryPolicy] governs retryable failures; [errorType] carries the error class for type-chain checks.
 */
data class StepDef<I, O, E>(
    override val name: String,
    val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 1),
    val fn: StepFn<I, O, E>,
) : OperatorDef()

/** Filters work items — outputs that fail [predicate] are dropped with [filteredReason]. */
data class FilterDef<T, E>(
    override val name: String,
    val filteredReason: FilteredReason = FilteredReason.BELOW_THRESHOLD,
    val predicate: StepFn<T, Boolean, E>,
) : OperatorDef()

/** Persists the current item value to the durable store after each successful step. */
data class PersistEachDef(
    override val name: String,
) : OperatorDef()

/**
 * Waits until all work items from [predecessorStep] have reached a terminal state
 * before allowing downstream operators to proceed.
 */
data class BarrierDef(
    override val name: String,
    val predecessorStep: String,
) : OperatorDef()

/** Runs exactly once per run after all barrier predecessors are satisfied. */
data class FinalizerDef<T, E>(
    override val name: String,
    val fn: StepFn<List<T>, Unit, E>,
) : OperatorDef()

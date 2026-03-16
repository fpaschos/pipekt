package io.github.fpaschos.pipekt.runtime

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.fpaschos.pipekt.core.FilterDef
import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.ItemFailure
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.core.SourceAdapter
import io.github.fpaschos.pipekt.core.SourceDef
import io.github.fpaschos.pipekt.core.StepCtx
import io.github.fpaschos.pipekt.core.StepDef
import io.github.fpaschos.pipekt.store.DurableStore
import io.github.fpaschos.pipekt.store.RunRecord
import io.github.fpaschos.pipekt.store.WorkItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.reflect.KType
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Store-backed execution engine for a single pipeline run.
 *
 * Lifecycle and public control live in actors. This engine owns only the execution loops and the
 * store interactions required to ingest, claim, execute, and checkpoint work.
 */
@OptIn(ExperimentalUuidApi::class)
internal class PipelineExecutionEngine(
    private val pipeline: PipelineDefinition,
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
    private val scope: CoroutineScope,
    private val planVersion: String,
    private val config: RuntimeConfig,
) {
    private val workerId: String = Uuid.random().toString()
    private val jobs = mutableListOf<Job>()

    private lateinit var runId: String

    @Suppress("UNCHECKED_CAST")
    private val operators: List<CompiledOperator> by lazy {
        pipeline.operators.mapIndexedNotNull { index, operator ->
            val nextStepName = pipeline.operators.getOrNull(index + 1)?.name
            when (operator) {
                is StepDef<*, *> -> CompiledOperator.Step(operator as StepDef<Any?, Any?>, nextStepName)
                is FilterDef<*> -> CompiledOperator.Filter(operator as FilterDef<Any?>, nextStepName)
                is SourceDef<*> -> null
            }
        }
    }

    private val sourcePayloadType: KType by lazy {
        operators.first().inputType
    }

    suspend fun start(): RunRecord {
        check(jobs.isEmpty()) { "PipelineExecutionEngine is already running for ${pipeline.name}" }

        val run = store.findOrCreateRun(pipeline.name, planVersion)
        runId = run.id
        store.reclaimExpiredLeases(Clock.System.now(), limit = Int.MAX_VALUE)

        launchIngestion()
        launchWorkers()
        return run
    }

    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        jobs.clear()
    }

    private fun launchIngestion() {
        val firstStepName = operators.firstOrNull()?.stepName ?: return
        val adapter = pipeline.source.adapter as SourceAdapter<Any?>

        jobs +=
            scope.launch {
                var failureBackoff = INITIAL_FAILURE_BACKOFF
                while (isActive) {
                    try {
                        val nonTerminal = store.countNonTerminal(runId)
                        if (nonTerminal < pipeline.maxInFlight) {
                            val maxPoll = pipeline.maxInFlight - nonTerminal
                            val records = adapter.poll(maxPoll)
                            if (records.isNotEmpty()) {
                                val ingress =
                                    records.map { record ->
                                        IngressRecord(
                                            sourceId = record.id,
                                            payload = serializer.serialize(record.payload, sourcePayloadType),
                                        )
                                    }
                                store.appendIngress(runId, ingress, firstStep = firstStepName)
                                adapter.ack(records)
                            }
                        }
                        failureBackoff = INITIAL_FAILURE_BACKOFF
                        delay(config.workerPollInterval)
                    } catch (t: Throwable) {
                        failureBackoff = handleLoopFailure(failureBackoff, t)
                    }
                }
            }
    }

    private fun launchWorkers() {
        operators.forEach { operator ->
            jobs +=
                scope.launch {
                    var failureBackoff = INITIAL_FAILURE_BACKOFF
                    while (isActive) {
                        try {
                            val claimed =
                                store.claim(
                                    step = operator.stepName,
                                    runId = runId,
                                    limit = config.workerClaimLimit,
                                    leaseDuration = config.leaseDuration,
                                    workerId = workerId,
                                )
                            for (item in claimed) {
                                executeAndCheckpoint(operator, item)
                            }
                            failureBackoff = INITIAL_FAILURE_BACKOFF
                            delay(config.workerPollInterval)
                        } catch (t: Throwable) {
                            failureBackoff = handleLoopFailure(failureBackoff, t)
                        }
                    }
                }
        }
    }

    private suspend fun handleLoopFailure(
        currentBackoff: Duration,
        failure: Throwable,
    ): Duration {
        if (failure is CancellationException) {
            throw failure
        }

        delay(currentBackoff)
        return (currentBackoff * 2).coerceAtMost(MAX_FAILURE_BACKOFF)
    }

    private suspend fun executeAndCheckpoint(
        operator: CompiledOperator,
        item: WorkItem,
    ) {
        when (operator) {
            is CompiledOperator.Step -> executeStep(operator, item)
            is CompiledOperator.Filter -> executeFilter(operator, item)
        }
    }

    private suspend fun executeStep(
        operator: CompiledOperator.Step,
        item: WorkItem,
    ) {
        val result =
            runStepFn<Any?, Any?>(item, operator.def.name, operator.def.inputType) { input ->
                operator.def.fn(input)
            }

        result.fold(
            ifLeft = { failure -> handleFailure(item, failure, operator.def) },
            ifRight = { output ->
                requireNotNull(output) {
                    "Step ${operator.def.name} returned null; output must be non-null for checkpoint."
                }
                val outputJson = serializer.serialize(output, operator.def.outputType)
                store.checkpointSuccess(item, outputJson, operator.nextStepName)
            },
        )
    }

    private suspend fun executeFilter(
        operator: CompiledOperator.Filter,
        item: WorkItem,
    ) {
        val result =
            runStepFn<Any?, Boolean>(item, operator.def.name, operator.def.inputType) { input ->
                operator.def.predicate(input)
            }

        result.fold(
            ifLeft = { failure ->
                if (failure.isFiltered()) {
                    store.checkpointFiltered(item, failure.message)
                } else {
                    handleFailure(item, failure, stepDef = null)
                }
            },
            ifRight = { keep ->
                if (keep) {
                    store.checkpointSuccess(item, item.payloadJson!!, operator.nextStepName)
                } else {
                    store.checkpointFiltered(item, operator.def.filteredReason.name)
                }
            },
        )
    }

    private suspend fun <I, R> runStepFn(
        item: WorkItem,
        stepName: String,
        inputType: KType,
        block: suspend context(Raise<ItemFailure>, StepCtx) (I) -> R,
    ): Either<ItemFailure, R> {
        val input = serializer.deserialize<I>(item.payloadJson!!, inputType)
        val ctx = buildCtx(item, stepName)
        return either { with(ctx) { block(input) } }
    }

    private fun buildCtx(
        item: WorkItem,
        stepName: String,
    ): StepCtx =
        StepCtx(
            pipelineName = pipeline.name,
            runId = runId,
            itemId = item.id,
            itemKey = item.sourceId,
            stepName = stepName,
            attempt = item.attemptCount + 1,
            startedAt = Clock.System.now().toEpochMilliseconds(),
        )

    private suspend fun handleFailure(
        item: WorkItem,
        failure: ItemFailure,
        stepDef: StepDef<*, *>?,
    ) {
        if (failure.isRetryable() && stepDef != null) {
            val attemptsUsed = item.attemptCount + 1
            if (attemptsUsed < stepDef.retryPolicy.maxAttempts) {
                val retryAt = Clock.System.now() + stepDef.retryPolicy.backoffMs.milliseconds
                store.checkpointFailure(item, failure.message, retryAt)
                return
            }
        }

        store.checkpointFailure(item, failure.message, retryAt = null)
    }

    companion object {
        private val INITIAL_FAILURE_BACKOFF: Duration = 100.milliseconds
        private val MAX_FAILURE_BACKOFF: Duration = 5.seconds
    }
}

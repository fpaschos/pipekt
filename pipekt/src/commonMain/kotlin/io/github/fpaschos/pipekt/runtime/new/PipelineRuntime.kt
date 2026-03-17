package io.github.fpaschos.pipekt.runtime.new

import arrow.core.Either
import arrow.core.raise.either
import io.github.fpaschos.pipekt.core.FilterDef
import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.ItemFailure
import io.github.fpaschos.pipekt.core.PayloadSerializer
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.core.SourceAdapter
import io.github.fpaschos.pipekt.core.SourceDef
import io.github.fpaschos.pipekt.core.SourceRecord
import io.github.fpaschos.pipekt.core.StepCtx
import io.github.fpaschos.pipekt.core.StepDef
import io.github.fpaschos.pipekt.store.DurableStore
import io.github.fpaschos.pipekt.store.RunRecord
import io.github.fpaschos.pipekt.store.WorkItem
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

internal class PipelineRuntime(
    val definition: PipelineDefinition,
    val planVersion: String,
    private val store: DurableStore,
    private val serializer: PayloadSerializer,
    private val config: RuntimeConfig,
) {
    @Suppress("UNCHECKED_CAST")
    private val compiledOperators: List<CompiledOperator> =
        definition.operators.mapIndexed { index, operator ->
            val nextStepName = definition.operators.getOrNull(index + 1)?.name
            when (operator) {
                is StepDef<*, *> -> {
                    CompiledOperator.Step(
                        def = operator as StepDef<Any?, Any?>,
                        nextStepName = nextStepName,
                    )
                }

                is FilterDef<*> -> {
                    CompiledOperator.Filter(
                        def = operator as FilterDef<Any?>,
                        nextStepName = nextStepName,
                    )
                }

                is SourceDef<*> -> {
                    error("Pipeline operators must not contain sources.")
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private val source = definition.source as SourceDef<Any?>
    private val operatorsByName = compiledOperators.associateBy { it.stepName }

    private val sourcePayloadType = compiledOperators.firstOrNull()?.inputType ?: error("Pipeline must define at least one operator.")

    private var startedRun: RunRecord? = null

    val runRecord: RunRecord
        get() = checkNotNull(startedRun) { "Pipeline runtime for ${definition.name} has not started yet." }

    val stepNames: List<String> = compiledOperators.map { it.stepName }

    suspend fun start(): RunRecord {
        startedRun?.let { return it }
        return store.findOrCreateRun(definition.name, planVersion).also { startedRun = it }
    }

    suspend fun remainingIngressCapacity(): Int {
        val active = store.countNonTerminal(runRecord.id)
        return (definition.maxInFlight - active).coerceAtLeast(0)
    }

    suspend fun ingest(maxItems: Int): Boolean {
        if (maxItems <= 0) {
            return false
        }

        val polled = source.adapter.poll(maxItems)
        if (polled.isEmpty()) {
            return false
        }

        try {
            val records =
                polled.map { record ->
                    IngressRecord(
                        sourceId = record.id,
                        payload = serializer.serialize(record.payload, sourcePayloadType),
                    )
                }
            store.appendIngress(
                runId = runRecord.id,
                records = records,
                firstStep = stepNames.first(),
            )
            source.adapter.ack(polled)
            return true
        } catch (t: Throwable) {
            source.adapter.nack(polled, retry = true)
            throw t
        }
    }

    suspend fun executeStep(
        stepName: String,
        workerId: String,
    ): Boolean {
        val operator = operatorsByName.getValue(stepName)
        val claimed =
            store.claim(
                step = stepName,
                runId = runRecord.id,
                limit = config.workerClaimLimit,
                leaseDuration = config.leaseDuration,
                workerId = workerId,
            )
        if (claimed.isEmpty()) {
            return false
        }

        claimed.forEach { item ->
            executeItem(operator, item)
        }
        return true
    }

    private suspend fun executeItem(
        operator: CompiledOperator,
        item: WorkItem,
    ) {
        when (operator) {
            is CompiledOperator.Step -> executeTransform(operator, item)
            is CompiledOperator.Filter -> executeFilter(operator, item)
        }
    }

    private suspend fun executeTransform(
        operator: CompiledOperator.Step,
        item: WorkItem,
    ) {
        val payloadJson = requireNotNull(item.payloadJson) { "Work item ${item.id} at ${operator.stepName} is missing payload." }
        val input = serializer.deserialize<Any?>(payloadJson, operator.inputType)
        val ctx = item.stepCtx(operator.stepName)
        val result: Either<ItemFailure, Any?> = either { with(ctx) { operator.def.fn(input) } }

        when (result) {
            is Either.Right -> {
                val outputJson = serializer.serialize(result.value, operator.def.outputType)
                store.checkpointSuccess(item, outputJson, operator.nextStepName)
            }

            is Either.Left -> {
                checkpointFailure(item, result.value, operator.def.retryPolicy.maxAttempts, operator.def.retryPolicy.backoffMs)
            }
        }
    }

    private suspend fun executeFilter(
        operator: CompiledOperator.Filter,
        item: WorkItem,
    ) {
        val payloadJson = requireNotNull(item.payloadJson) { "Work item ${item.id} at ${operator.stepName} is missing payload." }
        val input = serializer.deserialize<Any?>(payloadJson, operator.inputType)
        val ctx = item.stepCtx(operator.stepName)
        val result: Either<ItemFailure, Boolean> = either { with(ctx) { operator.def.predicate(input) } }

        when (result) {
            is Either.Right -> {
                if (result.value) {
                    store.checkpointSuccess(item, payloadJson, operator.nextStepName)
                } else {
                    store.checkpointFiltered(item, operator.def.filteredReason.name)
                }
            }

            is Either.Left -> {
                val failure = result.value
                if (failure.isFiltered()) {
                    store.checkpointFiltered(item, failure.message)
                } else {
                    checkpointFailure(item, failure, maxAttempts = 1, backoffMs = 0L)
                }
            }
        }
    }

    private suspend fun checkpointFailure(
        item: WorkItem,
        failure: ItemFailure,
        maxAttempts: Int,
        backoffMs: Long,
    ) {
        if (failure.isFiltered()) {
            store.checkpointFiltered(item, failure.message)
            return
        }

        val attempt = item.attemptCount + 1
        val retryAt =
            if (failure.isRetryable() && attempt < maxAttempts) {
                Clock.System.now() + backoffMs.milliseconds
            } else {
                null
            }

        store.checkpointFailure(
            item = item,
            errorJson = failure.message,
            retryAt = retryAt,
        )
    }

    private fun WorkItem.stepCtx(stepName: String): StepCtx {
        val now = Clock.System.now()
        return StepCtx(
            pipelineName = definition.name,
            runId = runId,
            itemId = id,
            itemKey = sourceId,
            stepName = stepName,
            attempt = attemptCount + 1,
            startedAt = now.toEpochMilliseconds(),
        )
    }
}

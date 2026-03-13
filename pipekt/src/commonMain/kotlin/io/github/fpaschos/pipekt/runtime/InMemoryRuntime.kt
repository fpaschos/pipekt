package io.github.fpaschos.pipekt.runtime

import arrow.core.Either
import arrow.core.raise.either
import io.github.fpaschos.pipekt.core.BarrierDef
import io.github.fpaschos.pipekt.core.BarrierResult
import io.github.fpaschos.pipekt.core.Clock
import io.github.fpaschos.pipekt.core.FilterDef
import io.github.fpaschos.pipekt.core.FinalizerDef
import io.github.fpaschos.pipekt.core.FinalizerStartResult
import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.ItemFailure
import io.github.fpaschos.pipekt.core.PersistEachDef
import io.github.fpaschos.pipekt.core.PipelineDefinition
import io.github.fpaschos.pipekt.core.RunStatus
import io.github.fpaschos.pipekt.core.SourceDef
import io.github.fpaschos.pipekt.core.StepCtx
import io.github.fpaschos.pipekt.core.StepDef
import io.github.fpaschos.pipekt.store.AttemptOutcome
import io.github.fpaschos.pipekt.store.AttemptRecord
import io.github.fpaschos.pipekt.store.DurableStore
import io.github.fpaschos.pipekt.store.RunRecord
import io.github.fpaschos.pipekt.store.WorkItemStatus
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * In-memory [PipelineRuntime] that drives pipeline execution step by step.
 *
 * Each [IngressRecord] is processed through every operator in [pipeline.operators]
 * sequentially. A [BarrierDef] operator suspends the pipeline until all predecessor
 * items have completed. A [FinalizerDef] is run exactly once per run via a durable lock.
 *
 * @param serializer used to serialize/deserialize item payloads to/from JSON strings.
 */
@OptIn(ExperimentalUuidApi::class)
class InMemoryRuntime(
    private val pipeline: PipelineDefinition,
    private val store: DurableStore,
    private val clock: Clock,
    private val serializer: PayloadSerializer = JsonPayloadSerializer,
) : PipelineRuntime {
    // ── PipelineRuntime ───────────────────────────────────────────────────────

    override suspend fun startRun(records: List<IngressRecord<*>>): RunRecord {
        val run = store.createRun(pipelineName = pipeline.name, nowMs = clock.nowMs())
        store.updateRunStatus(run.id, RunStatus.IN_PROGRESS, clock.nowMs())

        val firstStep = pipeline.operators.firstOrNull()?.name ?: return run

        for (record in records) {
            val payloadJson = serializer.serialize(record.payload)
            store.appendIngress(
                runId = run.id,
                record = record,
                payloadJson = payloadJson,
                stepName = firstStep,
                nowMs = clock.nowMs(),
            )
        }

        executeRun(run.id)
        return store.getRun(run.id) ?: run
    }

    override suspend fun resumeRuns() {
        val activeRuns = store.listActiveRuns(pipeline.name)
        for (run in activeRuns) {
            executeRun(run.id)
        }
    }

    override suspend fun ingestAndExecute(
        runId: String,
        record: IngressRecord<*>,
    ) {
        val firstStep = pipeline.operators.firstOrNull()?.name ?: return
        val payloadJson = serializer.serialize(record.payload)
        store.appendIngress(
            runId = runId,
            record = record,
            payloadJson = payloadJson,
            stepName = firstStep,
            nowMs = clock.nowMs(),
        )
        executeRun(runId)
    }

    // ── Execution engine ──────────────────────────────────────────────────────

    private suspend fun executeRun(runId: String) {
        for ((index, operator) in pipeline.operators.withIndex()) {
            val nextStep = pipeline.operators.getOrNull(index + 1)?.name

            when (operator) {
                is SourceDef<*> -> {
                    Unit
                }
                is StepDef<*, *, *> -> {
                    executeStep(runId, operator, nextStep)
                }
                is FilterDef<*, *> -> {
                    executeFilter(runId, operator, nextStep)
                }
                is PersistEachDef -> {
                    persistEach(runId, operator, nextStep)
                }
                is BarrierDef -> {
                    val result = store.evaluateBarrier(runId, operator.predecessorStep)
                    if (result == BarrierResult.WAITING) {
                        store.updateRunStatus(runId, RunStatus.AWAITING_BARRIER, clock.nowMs())
                        return
                    }
                }
                is FinalizerDef<*, *> -> {
                    val startResult = store.tryStartFinalizer(runId, clock.nowMs())
                    if (startResult == FinalizerStartResult.ALREADY_STARTED) return
                    @Suppress("UNCHECKED_CAST")
                    executeFinalizer(runId, operator as FinalizerDef<Any?, Any?>)
                }
            }
        }
        store.updateRunStatus(runId, RunStatus.FINALIZED, clock.nowMs())
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeStep(
        runId: String,
        op: StepDef<*, *, *>,
        nextStep: String?,
    ) {
        val step = op as StepDef<Any?, Any?, Any?>
        val items = store.claimPendingItems(runId, op.name)

        for (item in items) {
            val payload = serializer.deserialize(item.payloadJson)

            for (attempt in 1..step.retryPolicy.maxAttempts) {
                val ctx = StepCtx(runId = runId, stepName = op.name, attemptNumber = attempt)
                val startMs = clock.nowMs()

                val result: Either<Any?, Any?> =
                    either<Any?, Any?> {
                        with(ctx) { step.fn(payload) }
                    }

                val finishMs = clock.nowMs()
                val isFinal = attempt >= step.retryPolicy.maxAttempts

                when {
                    result.isRight() -> {
                        store.recordAttempt(
                            AttemptRecord(
                                id = Uuid.random().toString(),
                                workItemId = item.id,
                                runId = runId,
                                stepName = op.name,
                                attemptNumber = attempt,
                                outcome = AttemptOutcome.SUCCESS,
                                failure = null,
                                startedAtMs = startMs,
                                finishedAtMs = finishMs,
                            ),
                        )
                        val outJson = serializer.serialize(result.getOrNull())
                        store.checkpointItem(
                            workItemId = item.id,
                            nextStep = nextStep ?: op.name,
                            status = if (nextStep != null) WorkItemStatus.PENDING else WorkItemStatus.COMPLETED,
                            payloadJson = outJson,
                            nowMs = clock.nowMs(),
                        )
                        break
                    }
                    else -> {
                        val errorMsg = result.leftOrNull()?.toString() ?: "unknown"
                        val failure =
                            if (isFinal) {
                                ItemFailure.Fatal(errorMsg)
                            } else {
                                ItemFailure.Retryable(cause = errorMsg, attemptNumber = attempt)
                            }
                        store.recordAttempt(
                            AttemptRecord(
                                id = Uuid.random().toString(),
                                workItemId = item.id,
                                runId = runId,
                                stepName = op.name,
                                attemptNumber = attempt,
                                outcome =
                                    if (isFinal) {
                                        AttemptOutcome.FATAL_FAILURE
                                    } else {
                                        AttemptOutcome.RETRYABLE_FAILURE
                                    },
                                failure = failure,
                                startedAtMs = startMs,
                                finishedAtMs = finishMs,
                            ),
                        )
                        if (isFinal) {
                            store.failItem(item.id, op.name, failure, clock.nowMs())
                        }
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeFilter(
        runId: String,
        op: FilterDef<*, *>,
        nextStep: String?,
    ) {
        val filter = op as FilterDef<Any?, Any?>
        val items = store.claimPendingItems(runId, op.name)

        for (item in items) {
            val payload = serializer.deserialize(item.payloadJson)
            val ctx = StepCtx(runId = runId, stepName = op.name, attemptNumber = 1)
            val startMs = clock.nowMs()

            val result: Either<Any?, Boolean> =
                either<Any?, Boolean> {
                    with(ctx) { filter.predicate(payload) }
                }

            val finishMs = clock.nowMs()

            when {
                result.isLeft() -> {
                    val failure = ItemFailure.Fatal(result.leftOrNull()?.toString() ?: "unknown")
                    store.recordAttempt(
                        AttemptRecord(
                            id = Uuid.random().toString(),
                            workItemId = item.id,
                            runId = runId,
                            stepName = op.name,
                            attemptNumber = 1,
                            outcome = AttemptOutcome.FATAL_FAILURE,
                            failure = failure,
                            startedAtMs = startMs,
                            finishedAtMs = finishMs,
                        ),
                    )
                    store.failItem(item.id, op.name, failure, clock.nowMs())
                }
                result.getOrNull() == true -> {
                    store.recordAttempt(
                        AttemptRecord(
                            id = Uuid.random().toString(),
                            workItemId = item.id,
                            runId = runId,
                            stepName = op.name,
                            attemptNumber = 1,
                            outcome = AttemptOutcome.SUCCESS,
                            failure = null,
                            startedAtMs = startMs,
                            finishedAtMs = finishMs,
                        ),
                    )
                    store.checkpointItem(
                        workItemId = item.id,
                        nextStep = nextStep ?: op.name,
                        status = if (nextStep != null) WorkItemStatus.PENDING else WorkItemStatus.COMPLETED,
                        payloadJson = item.payloadJson,
                        nowMs = clock.nowMs(),
                    )
                }
                else -> {
                    val failure = ItemFailure.Filtered(op.filteredReason)
                    store.recordAttempt(
                        AttemptRecord(
                            id = Uuid.random().toString(),
                            workItemId = item.id,
                            runId = runId,
                            stepName = op.name,
                            attemptNumber = 1,
                            outcome = AttemptOutcome.FILTERED,
                            failure = failure,
                            startedAtMs = startMs,
                            finishedAtMs = finishMs,
                        ),
                    )
                    store.checkpointItem(
                        workItemId = item.id,
                        nextStep = op.name,
                        status = WorkItemStatus.FILTERED,
                        payloadJson = item.payloadJson,
                        nowMs = clock.nowMs(),
                    )
                }
            }
        }
    }

    private suspend fun persistEach(
        runId: String,
        op: PersistEachDef,
        nextStep: String?,
    ) {
        val items = store.claimPendingItems(runId, op.name)
        for (item in items) {
            store.checkpointItem(
                workItemId = item.id,
                nextStep = nextStep ?: op.name,
                status = if (nextStep != null) WorkItemStatus.PENDING else WorkItemStatus.COMPLETED,
                payloadJson = item.payloadJson,
                nowMs = clock.nowMs(),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeFinalizer(
        runId: String,
        op: FinalizerDef<Any?, Any?>,
    ) {
        val allItems = store.getItemsForStep(runId, op.name)
        val payloads =
            allItems
                .filter { it.status == WorkItemStatus.COMPLETED || it.status == WorkItemStatus.PENDING }
                .map { serializer.deserialize(it.payloadJson) }

        val ctx = StepCtx(runId = runId, stepName = op.name, attemptNumber = 1)

        either<Any?, Unit> {
            with(ctx) { op.fn(payloads) }
        }

        store.completeFinalizer(runId, clock.nowMs())
    }
}

// ── Payload serializer SPI ────────────────────────────────────────────────────

interface PayloadSerializer {
    fun serialize(value: Any?): String

    fun deserialize(json: String): Any?
}

/**
 * Simple serializer that stores the value as its [toString] representation.
 * Suitable only for [String] and primitive payloads in tests.
 */
object JsonPayloadSerializer : PayloadSerializer {
    override fun serialize(value: Any?): String = value?.toString() ?: "null"

    override fun deserialize(json: String): Any? = if (json == "null") null else json
}

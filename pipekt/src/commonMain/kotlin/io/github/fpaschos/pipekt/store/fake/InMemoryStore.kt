package io.github.fpaschos.pipekt.store.fake

import io.github.fpaschos.pipekt.core.AppendIngressResult
import io.github.fpaschos.pipekt.core.BarrierResult
import io.github.fpaschos.pipekt.core.FinalizerStartResult
import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.ItemFailure
import io.github.fpaschos.pipekt.core.RunStatus
import io.github.fpaschos.pipekt.store.AttemptRecord
import io.github.fpaschos.pipekt.store.DurableStore
import io.github.fpaschos.pipekt.store.RunRecord
import io.github.fpaschos.pipekt.store.WorkItem
import io.github.fpaschos.pipekt.store.WorkItemStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class InMemoryStore : DurableStore {
    private val mutex = Mutex()

    private val runs = mutableMapOf<String, RunRecord>()
    private val workItems = mutableMapOf<String, WorkItem>()
    private val attempts = mutableMapOf<String, MutableList<AttemptRecord>>()
    private val finalizerLocks = mutableSetOf<String>()

    // ── Run lifecycle ─────────────────────────────────────────────────────────

    override suspend fun createRun(
        pipelineName: String,
        nowMs: Long,
    ): RunRecord =
        mutex.withLock {
            val record =
                RunRecord(
                    id = Uuid.random().toString(),
                    pipelineName = pipelineName,
                    status = RunStatus.PENDING,
                    createdAtMs = nowMs,
                    updatedAtMs = nowMs,
                )
            runs[record.id] = record
            record
        }

    override suspend fun getRun(runId: String): RunRecord? =
        mutex.withLock {
            runs[runId]
        }

    override suspend fun updateRunStatus(
        runId: String,
        status: RunStatus,
        nowMs: Long,
    ): Unit =
        mutex.withLock {
            runs[runId]?.let { runs[runId] = it.copy(status = status, updatedAtMs = nowMs) }
        }

    override suspend fun listActiveRuns(pipelineName: String): List<RunRecord> =
        mutex.withLock {
            val terminal = setOf(RunStatus.FINALIZED, RunStatus.FAILED)
            runs.values.filter { it.pipelineName == pipelineName && it.status !in terminal }
        }

    // ── Ingress / work-item lifecycle ─────────────────────────────────────────

    override suspend fun appendIngress(
        runId: String,
        record: IngressRecord<*>,
        payloadJson: String,
        stepName: String,
        nowMs: Long,
    ): AppendIngressResult =
        mutex.withLock {
            val existing = workItems.values.any { it.runId == runId && it.sourceId == record.sourceId }
            if (existing) return@withLock AppendIngressResult.DUPLICATE

            val item =
                WorkItem(
                    id = Uuid.random().toString(),
                    runId = runId,
                    sourceId = record.sourceId,
                    currentStep = stepName,
                    status = WorkItemStatus.PENDING,
                    payloadJson = payloadJson,
                    createdAtMs = nowMs,
                    updatedAtMs = nowMs,
                )
            workItems[item.id] = item
            AppendIngressResult.APPENDED
        }

    override suspend fun claimPendingItems(
        runId: String,
        stepName: String,
        max: Int,
    ): List<WorkItem> =
        mutex.withLock {
            val pending =
                workItems.values
                    .filter { it.runId == runId && it.currentStep == stepName && it.status == WorkItemStatus.PENDING }
                    .take(max)

            pending.forEach { item ->
                workItems[item.id] = item.copy(status = WorkItemStatus.IN_PROGRESS)
            }
            pending.map { it.copy(status = WorkItemStatus.IN_PROGRESS) }
        }

    override suspend fun getItemsForStep(
        runId: String,
        stepName: String,
    ): List<WorkItem> =
        mutex.withLock {
            workItems.values.filter { it.runId == runId && it.currentStep == stepName }
        }

    override suspend fun checkpointItem(
        workItemId: String,
        nextStep: String,
        status: WorkItemStatus,
        payloadJson: String,
        nowMs: Long,
    ): Unit =
        mutex.withLock {
            workItems[workItemId]?.let { item ->
                workItems[workItemId] =
                    item.copy(
                        currentStep = nextStep,
                        status = status,
                        payloadJson = payloadJson,
                        updatedAtMs = nowMs,
                    )
            }
        }

    // ── Attempt records ───────────────────────────────────────────────────────

    override suspend fun recordAttempt(attempt: AttemptRecord): Unit =
        mutex.withLock {
            attempts.getOrPut(attempt.workItemId) { mutableListOf() }.add(attempt)
        }

    override suspend fun getAttempts(workItemId: String): List<AttemptRecord> =
        mutex.withLock {
            attempts[workItemId]?.sortedBy { it.attemptNumber } ?: emptyList()
        }

    // ── Barrier ───────────────────────────────────────────────────────────────

    override suspend fun evaluateBarrier(
        runId: String,
        predecessorStep: String,
    ): BarrierResult =
        mutex.withLock {
            val allRunItems = workItems.values.filter { it.runId == runId }
            if (allRunItems.isEmpty()) return@withLock BarrierResult.WAITING

            val blocking = setOf(WorkItemStatus.PENDING, WorkItemStatus.IN_PROGRESS)
            val stillBlocking = allRunItems.any { it.currentStep == predecessorStep && it.status in blocking }
            if (stillBlocking) BarrierResult.WAITING else BarrierResult.READY
        }

    // ── Finalizer ─────────────────────────────────────────────────────────────

    override suspend fun tryStartFinalizer(
        runId: String,
        nowMs: Long,
    ): FinalizerStartResult =
        mutex.withLock {
            if (finalizerLocks.contains(runId)) {
                FinalizerStartResult.ALREADY_STARTED
            } else {
                finalizerLocks.add(runId)
                FinalizerStartResult.STARTED
            }
        }

    override suspend fun completeFinalizer(
        runId: String,
        nowMs: Long,
    ): Unit =
        mutex.withLock {
            runs[runId]?.let { runs[runId] = it.copy(status = RunStatus.FINALIZED, updatedAtMs = nowMs) }
        }

    // ── Failure handling ──────────────────────────────────────────────────────

    override suspend fun failItem(
        workItemId: String,
        stepName: String,
        failure: ItemFailure,
        nowMs: Long,
    ): Unit =
        mutex.withLock {
            workItems[workItemId]?.let { item ->
                workItems[workItemId] =
                    item.copy(
                        currentStep = stepName,
                        status = WorkItemStatus.FAILED,
                        updatedAtMs = nowMs,
                    )
            }
        }

    // ── Test helpers ──────────────────────────────────────────────────────────

    /** Returns a snapshot of all work items — useful for assertions in tests. */
    suspend fun allWorkItems(): List<WorkItem> = mutex.withLock { workItems.values.toList() }

    /** Returns a snapshot of all run records. */
    suspend fun allRuns(): List<RunRecord> = mutex.withLock { runs.values.toList() }
}

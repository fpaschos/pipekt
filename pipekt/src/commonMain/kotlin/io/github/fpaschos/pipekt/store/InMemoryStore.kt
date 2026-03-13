package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.WorkItemStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * In-memory implementation of [DurableStore] for testing and development.
 *
 * All state is held in `MutableMap`s guarded by a single [Mutex]. This ensures that concurrent
 * coroutines in tests cannot observe partial writes across checkpoint operations.
 *
 * **Payload serialization:** [InMemoryStore] stores `payloadJson` verbatim from [IngressRecord.payload]
 * cast to [String]. The caller (runtime) is responsible for serializing payloads to JSON strings
 * before passing them to [appendIngress]. Tests may pass raw JSON strings via `IngressRecord<String>`.
 *
 * **Lease semantics:** [claim] uses `Clock.System.now()` internally for lease expiry computation.
 * [reclaimExpiredLeases] accepts an explicit `now` parameter so the caller (runtime watchdog) controls
 * when expiry is evaluated — this makes lease reclaim deterministic and testable.
 *
 * This class also exposes [getWorkItem] and [markRunFailed] as additional helpers required by tests
 * and operational tooling.
 *
 * See `plans/streams-contracts-v1.md` (Store Contracts) and `plans/streams-delivery-phases.md` (Phase 1C).
 */
@OptIn(ExperimentalUuidApi::class)
class InMemoryStore : DurableStore {
    private val mutex = Mutex()
    private val runs = mutableMapOf<String, RunRecord>()
    private val items = mutableMapOf<String, WorkItem>()

    /** Dedup index: `(runId, sourceId)` → `itemId`. Used by [appendIngress] for idempotency. */
    private val ingressIndex = mutableMapOf<Pair<String, String>, String>()

    // ── Run lifecycle ──────────────────────────────────────────────────────────

    override suspend fun getOrCreateRun(
        pipeline: String,
        planVersion: String,
    ): RunRecord =
        mutex.withLock {
            runs.values.firstOrNull { it.pipeline == pipeline && it.planVersion == planVersion }
                ?: run {
                    val now = Clock.System.now()
                    RunRecord(
                        id = Uuid.random().toString(),
                        pipeline = pipeline,
                        planVersion = planVersion,
                        status = RunRecord.STATUS_ACTIVE,
                        createdAt = now,
                        updatedAt = now,
                    ).also { runs[it.id] = it }
                }
        }

    override suspend fun getRun(runId: String): RunRecord? = mutex.withLock { runs[runId] }

    override suspend fun listActiveRuns(pipeline: String): List<RunRecord> =
        mutex.withLock {
            runs.values.filter { it.pipeline == pipeline && it.status != RunRecord.STATUS_FAILED }
        }

    // ── Ingress ────────────────────────────────────────────────────────────────

    /**
     * Appends [records] to [runId], skipping any whose `(runId, sourceId)` pair already exists.
     *
     * The [firstStep] parameter tells the store which step name to assign as [WorkItem.currentStep]
     * for newly created items. The runtime supplies this when calling appendIngress.
     *
     * @param runId Run to append to.
     * @param records Ingress records; [IngressRecord.payload] must already be a serialized JSON string.
     * @param firstStep Name of the first operator step; assigned as [WorkItem.currentStep].
     * @return [AppendIngressResult] with counts of appended and duplicate records.
     */
    suspend fun appendIngress(
        runId: String,
        records: List<IngressRecord<*>>,
        firstStep: String,
    ): AppendIngressResult =
        mutex.withLock {
            var appended = 0
            var duplicates = 0
            val now = Clock.System.now()
            for (record in records) {
                val key = Pair(runId, record.sourceId)
                if (ingressIndex.containsKey(key)) {
                    duplicates++
                } else {
                    val itemId = record.id.toString()
                    val item =
                        WorkItem(
                            id = itemId,
                            runId = runId,
                            sourceId = record.sourceId,
                            currentStep = firstStep,
                            status = WorkItemStatus.PENDING,
                            payloadJson = record.payload as? String,
                            lastErrorJson = null,
                            attemptCount = 0,
                            leaseOwner = null,
                            leaseExpiry = null,
                            retryAt = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                    items[itemId] = item
                    ingressIndex[key] = itemId
                    appended++
                }
            }
            AppendIngressResult(appended = appended, duplicates = duplicates)
        }

    /**
     * Delegates to the internal [appendIngress] that accepts a [firstStep] parameter.
     *
     * This override satisfies the [DurableStore] interface. The [firstStep] defaults to an empty
     * string; callers that need step routing must use the overload with [firstStep] directly
     * (the runtime always uses that overload).
     */
    override suspend fun appendIngress(
        runId: String,
        records: List<IngressRecord<*>>,
    ): AppendIngressResult = appendIngress(runId, records, firstStep = "")

    // ── Claim ──────────────────────────────────────────────────────────────────

    override suspend fun claim(
        step: String,
        runId: String,
        limit: Int,
        leaseDuration: kotlin.time.Duration,
        workerId: String,
    ): List<WorkItem> =
        mutex.withLock {
            val now = Clock.System.now()
            val candidates =
                items.values
                    .filter { it.runId == runId && it.currentStep == step && it.status == WorkItemStatus.PENDING }
                    .take(limit)
            val leaseExpiry = now + leaseDuration
            candidates.map { item ->
                item
                    .copy(
                        status = WorkItemStatus.IN_PROGRESS,
                        leaseOwner = workerId,
                        leaseExpiry = leaseExpiry,
                        updatedAt = now,
                    ).also { items[item.id] = it }
            }
        }

    // ── Checkpoints ────────────────────────────────────────────────────────────

    override suspend fun checkpointSuccess(
        item: WorkItem,
        outputJson: String,
        nextStep: String?,
    ): Unit =
        mutex.withLock {
            val now = Clock.System.now()
            val updated =
                if (nextStep == null) {
                    item.copy(
                        status = WorkItemStatus.COMPLETED,
                        payloadJson = null,
                        leaseOwner = null,
                        leaseExpiry = null,
                        attemptCount = item.attemptCount + 1,
                        updatedAt = now,
                    )
                } else {
                    item.copy(
                        currentStep = nextStep,
                        status = WorkItemStatus.PENDING,
                        payloadJson = outputJson,
                        leaseOwner = null,
                        leaseExpiry = null,
                        attemptCount = item.attemptCount + 1,
                        updatedAt = now,
                    )
                }
            items[item.id] = updated
        }

    override suspend fun checkpointFiltered(
        item: WorkItem,
        reason: String,
    ): Unit =
        mutex.withLock {
            items[item.id] =
                item.copy(
                    status = WorkItemStatus.FILTERED,
                    payloadJson = null,
                    lastErrorJson = reason,
                    leaseOwner = null,
                    leaseExpiry = null,
                    attemptCount = item.attemptCount + 1,
                    updatedAt = Clock.System.now(),
                )
        }

    override suspend fun checkpointFailure(
        item: WorkItem,
        errorJson: String,
        retryAt: Instant?,
    ): Unit =
        mutex.withLock {
            val now = Clock.System.now()
            val updated =
                if (retryAt != null) {
                    // Retryable: keep payload, schedule retry
                    item.copy(
                        status = WorkItemStatus.PENDING,
                        lastErrorJson = errorJson,
                        retryAt = retryAt,
                        leaseOwner = null,
                        leaseExpiry = null,
                        attemptCount = item.attemptCount + 1,
                        updatedAt = now,
                    )
                } else {
                    // Fatal: null payload, mark FAILED
                    item.copy(
                        status = WorkItemStatus.FAILED,
                        payloadJson = null,
                        lastErrorJson = errorJson,
                        leaseOwner = null,
                        leaseExpiry = null,
                        attemptCount = item.attemptCount + 1,
                        updatedAt = now,
                    )
                }
            items[item.id] = updated
        }

    // ── Backpressure ───────────────────────────────────────────────────────────

    override suspend fun countNonTerminal(runId: String): Int =
        mutex.withLock {
            items.values.count {
                it.runId == runId &&
                    (it.status == WorkItemStatus.PENDING || it.status == WorkItemStatus.IN_PROGRESS)
            }
        }

    // ── Lease reclaim ──────────────────────────────────────────────────────────

    override suspend fun reclaimExpiredLeases(
        now: Instant,
        limit: Int,
    ): List<WorkItem> =
        mutex.withLock {
            val expired =
                items.values
                    .filter {
                        it.status == WorkItemStatus.IN_PROGRESS &&
                            it.leaseExpiry != null &&
                            it.leaseExpiry < now
                    }.take(limit)
            expired.map { item ->
                item
                    .copy(
                        status = WorkItemStatus.PENDING,
                        leaseOwner = null,
                        leaseExpiry = null,
                        updatedAt = Clock.System.now(),
                    ).also { items[item.id] = it }
            }
        }

    // ── Test / operational helpers ─────────────────────────────────────────────

    /**
     * Retrieves a single [WorkItem] by id. Used by tests to assert post-checkpoint state.
     *
     * @param itemId The work item id.
     * @return The [WorkItem], or null if not found.
     */
    suspend fun getWorkItem(itemId: String): WorkItem? = mutex.withLock { items[itemId] }

    /**
     * Forces a run identified by `(pipeline, planVersion)` into [RunRecord.STATUS_FAILED].
     * Used by tests to set up [listActiveRuns] filtering scenarios.
     *
     * @param pipeline Pipeline name.
     * @param planVersion Plan version of the run to fail.
     */
    suspend fun markRunFailed(
        pipeline: String,
        planVersion: String,
    ): Unit =
        mutex.withLock {
            val run =
                runs.values.firstOrNull { it.pipeline == pipeline && it.planVersion == planVersion }
                    ?: return@withLock
            runs[run.id] = run.copy(status = RunRecord.STATUS_FAILED, updatedAt = Clock.System.now())
        }

    /**
     * Retrieves a [WorkItem] by `(runId, sourceId)`. Used by tests to assert on per-item
     * terminal state after pipeline execution.
     *
     * @param runId The run the item belongs to.
     * @param sourceId The source-level identifier of the item.
     * @return The [WorkItem], or null if not found.
     */
    suspend fun getWorkItemBySourceId(
        runId: String,
        sourceId: String,
    ): WorkItem? =
        mutex.withLock {
            val itemId = ingressIndex[Pair(runId, sourceId)] ?: return@withLock null
            items[itemId]
        }

    /**
     * Returns all [WorkItem]s for the given run. Used by tests to assert counts and bulk state.
     *
     * @param runId The run id.
     * @return All work items belonging to the run, in an unspecified order.
     */
    suspend fun getAllWorkItems(runId: String): List<WorkItem> = mutex.withLock { items.values.filter { it.runId == runId } }
}

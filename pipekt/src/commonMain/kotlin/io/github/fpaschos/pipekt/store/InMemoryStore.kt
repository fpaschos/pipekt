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

    /**
     * Returns the existing active run for `(pipeline, planVersion)` or creates a new run.
     *
     * Implements [DurableStore.findOrCreateRun]. This implementation uses a single mutex;
     * lookup and creation are atomic. Run ids are generated via [Uuid].
     *
     * @param pipeline Pipeline name.
     * @param planVersion Version of the pipeline plan (for compatibility and schema evolution).
     * @return The run record; newly created or existing matching run.
     */
    override suspend fun findOrCreateRun(
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

    /**
     * Loads a run by id.
     *
     * Implements [DurableStore.findRun].
     *
     * @param runId Unique run identifier.
     * @return The run record, or null if not found.
     */
    override suspend fun findRun(runId: String): RunRecord? = mutex.withLock { runs[runId] }

    /**
     * Lists runs for the pipeline that are considered active (not [RunRecord.STATUS_FAILED]).
     *
     * Implements [DurableStore.findAllActiveRuns]. Order is unspecified.
     *
     * @param pipeline Pipeline name.
     * @return List of active run records.
     */
    override suspend fun findAllActiveRuns(pipeline: String): List<RunRecord> =
        mutex.withLock {
            runs.values.filter { it.pipeline == pipeline && it.status != RunRecord.STATUS_FAILED }
        }

    // ── Ingress ────────────────────────────────────────────────────────────────

    /**
     * Appends [records] to [runId], skipping any whose `(runId, sourceId)` pair already exists.
     *
     * [firstStep] is assigned as [WorkItem.currentStep] for newly created items. The runtime
     * supplies it when calling [appendIngress]; default is empty string for interface compatibility.
     *
     * @param runId Run to append to.
     * @param records Ingress records; [IngressRecord.payload] must already be a serialized JSON string.
     * @param firstStep Name of the first operator step; assigned as [WorkItem.currentStep].
     * @return [AppendIngressResult] with counts of appended and duplicate records.
     */
    override suspend fun appendIngress(
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

    // ── Claim ──────────────────────────────────────────────────────────────────

    /**
     * Claims up to [limit] work items for the given step and run under a lease.
     *
     * Implements [DurableStore.claim]. Only [WorkItemStatus.PENDING] items with matching
     * [runId] and [step] are eligible; lease expiry is computed from [Clock.System.now] + [leaseDuration].
     * This implementation does not consider expired leases when selecting candidates (no
     * `IN_PROGRESS` items are reclaimed here); use [reclaimExpiredLeases] to reset stuck items.
     *
     * @param step Step name to claim for.
     * @param runId Run id.
     * @param limit Maximum number of items to claim.
     * @param leaseDuration Lease duration from current time.
     * @param workerId Identifier of the worker claiming the items.
     * @return List of claimed [WorkItem]s (at most [limit]).
     */
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
                    .filter {
                        it.runId == runId &&
                            it.currentStep == step &&
                            it.status == WorkItemStatus.PENDING &&
                            (it.retryAt == null || it.retryAt <= now)
                    }
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

    /**
     * Marks the work item as completed successfully and advances it to the next step or terminal.
     *
     * Implements [DurableStore.checkpointSuccess]. Atomic under the store mutex: increments [WorkItem.attemptCount],
     * sets [WorkItem.currentStep] and [WorkItem.status], nulls [WorkItem.payloadJson] when [nextStep] is null,
     * clears lease fields, and sets [WorkItem.updatedAt].
     *
     * @param item The work item to checkpoint.
     * @param outputJson Serialized output payload for the next step (or terminal marker).
     * @param nextStep Next step name, or null if the item has reached a terminal state.
     */
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

    /**
     * Marks the work item as filtered out (e.g. dropped by a filter step).
     *
     * Implements [DurableStore.checkpointFiltered]. Atomic under the store mutex: sets status to
     * [WorkItemStatus.FILTERED], nulls [WorkItem.payloadJson], sets [WorkItem.lastErrorJson] to [reason],
     * increments [WorkItem.attemptCount], clears lease fields, and sets [WorkItem.updatedAt].
     *
     * @param item The work item to checkpoint.
     * @param reason Human- or machine-readable reason for filtering.
     */
    override suspend fun checkpointFiltered(
        item: WorkItem,
        reason: String,
    ): Unit =
        mutex.withLock {
            items[item.id] =
                item.copy(
                    status = WorkItemStatus.FILTERED,
                    payloadJson = null,
                    leaseOwner = null,
                    leaseExpiry = null,
                    attemptCount = item.attemptCount + 1,
                    updatedAt = Clock.System.now(),
                )
        }

    /**
     * Marks the work item as failed; it may be retried later if [retryAt] is set.
     *
     * Implements [DurableStore.checkpointFailure]. Atomic under the store mutex: when [retryAt] is non-null,
     * item remains [WorkItemStatus.PENDING] with [WorkItem.retryAt] set for later claim; when null,
     * item is set to [WorkItemStatus.FAILED] and [WorkItem.payloadJson] is nulled. In both cases
     * [WorkItem.attemptCount] is incremented and lease fields are cleared.
     *
     * @param item The work item to checkpoint.
     * @param errorJson Serialized error information.
     * @param retryAt Instant when the item may be retried, or null if no retry is scheduled.
     */
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
                        leaseOwner = null,
                        leaseExpiry = null,
                        attemptCount = item.attemptCount + 1,
                        updatedAt = now,
                    )
                }
            items[item.id] = updated
        }

    // ── Backpressure ───────────────────────────────────────────────────────────

    /**
     * Returns the number of work items in the run that are not in a terminal status.
     *
     * Implements [DurableStore.countNonTerminal]. Counts items with status [WorkItemStatus.PENDING]
     * or [WorkItemStatus.IN_PROGRESS]. Used by the runtime ingestion loop to enforce `maxInFlight` backpressure.
     *
     * @param runId Run id.
     * @return Count of non-terminal work items.
     */
    override suspend fun countNonTerminal(runId: String): Int =
        mutex.withLock {
            items.values.count {
                it.runId == runId &&
                    (it.status == WorkItemStatus.PENDING || it.status == WorkItemStatus.IN_PROGRESS)
            }
        }

    // ── Lease reclaim ──────────────────────────────────────────────────────────

    /**
     * Reclaims work items whose lease has expired by [now].
     *
     * Implements [DurableStore.reclaimExpiredLeases]. Resets [WorkItemStatus.IN_PROGRESS] items
     * whose [WorkItem.leaseExpiry] is before [now] back to [WorkItemStatus.PENDING], clearing
     * [WorkItem.leaseOwner] and [WorkItem.leaseExpiry]. The caller (runtime watchdog) supplies
     * [now] so that expiry evaluation is deterministic and testable.
     *
     * @param now Cutoff instant; items with [WorkItem.leaseExpiry] before this are reclaimed.
     * @param limit Maximum number of items to reclaim in one call.
     * @return List of reclaimed [WorkItem]s (at most [limit]).
     */
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

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
import io.github.fpaschos.pipekt.core.StepCtx
import io.github.fpaschos.pipekt.core.StepDef
import io.github.fpaschos.pipekt.store.DurableStore
import io.github.fpaschos.pipekt.store.InMemoryStore
import io.github.fpaschos.pipekt.store.WorkItem
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
 * The pipeline execution engine.
 *
 * `PipelineRuntime` owns execution of a single [PipelineDefinition]. It runs three independent
 * coroutine loops on the provided [scope]:
 *
 * 1. **Ingestion loop** — polls the source adapter, serializes payloads, bulk-appends to the
 *    store, then acks. Pauses when `countNonTerminal >= maxInFlight` (backpressure).
 * 2. **Worker loop** — one per [StepDef]/[FilterDef]; claims items for its step and executes
 *    the step function inside an `either {}` block, then checkpoints the result.
 * 3. **Watchdog loop** — periodically calls [DurableStore.reclaimExpiredLeases] to reset stuck
 *    `IN_PROGRESS` items back to `PENDING`.
 *
 * The three loops run concurrently and do not call each other. Durability comes entirely from the
 * injected [store]; passing an [InMemoryStore] gives a fully in-memory engine for testing.
 *
 * **Serialization:** [serializer] is used to serialize source payloads before ingestion and to
 * deserialize `payloadJson` before passing to step functions.
 *
 * **Context receivers:** [io.github.fpaschos.pipekt.core.StepFn] uses Kotlin context parameters
 * (`context(Raise<ItemFailure>, StepCtx)`). The runtime constructs both contexts and invokes the
 * function via `either { with(ctx) { fn(input) } }`.
 *
 * **Lease duration:** worker loops claim items with a fixed 30-second lease. Override via
 * [leaseDuration] when constructing for longer-running steps (see `plans/streams-technical-requirements.md`).
 *
 * **[FilterDef] semantics:** the predicate returns `Boolean` — `true` keeps the item (advances to
 * the next step), `false` filters it out. A raised [ItemFailure] that [ItemFailure.isFiltered]
 * also filters; other failures are handled as errors.
 *
 * **Worker claim limit:** each worker loop claims up to [workerClaimLimit] items per poll cycle.
 * Default is 10; increase for higher throughput (see `plans/streams-technical-requirements.md`).
 *
 * See `plans/streams-contracts-v1.md` (Pipeline Runtime section),
 * `plans/streams-delivery-phases.md` (Phase 1D), and
 * `plans/streams-technical-requirements.md` (defaults and recommended ranges).
 *
 * @param definition Validated pipeline to execute.
 * @param store [DurableStore] implementation providing durability and progress tracking.
 * @param serializer [PayloadSerializer] for payload serialization at ingestion and deserialization at execution.
 * @param scope [CoroutineScope] on which all runtime loops are launched. Inject a [TestScope] for deterministic testing.
 * @param planVersion Version key passed to [DurableStore.getOrCreateRun]; bump on incompatible plan changes.
 * @param workerPollInterval How often the ingestion and worker loops poll when idle (default 10ms; use 100–500ms in production).
 * @param watchdogInterval How often the watchdog calls [DurableStore.reclaimExpiredLeases] (default 50ms; use 1–5s in production).
 * @param leaseDuration How long a claimed item's lease lasts before the watchdog may reclaim it (default 30s).
 * @param workerClaimLimit Maximum items claimed per worker loop per poll cycle (default 10).
 */
@OptIn(ExperimentalUuidApi::class)
class PipelineRuntime(
    val definition: PipelineDefinition,
    val store: DurableStore,
    val serializer: PayloadSerializer,
    val scope: CoroutineScope,
    val planVersion: String = "v1",
    val workerPollInterval: Duration = 10.milliseconds,
    val watchdogInterval: Duration = 50.milliseconds,
    val leaseDuration: Duration = 30.seconds,
    val workerClaimLimit: Int = 10,
) {
    private val workerId: String = Uuid.random().toString()
    private val jobs = mutableListOf<Job>()

    /** The run id obtained from [DurableStore.getOrCreateRun] on [start]. */
    private lateinit var runId: String

    /**
     * Precomputed ordered list of executable operators ([StepDef] and [FilterDef] only).
     * Used by worker loop launching and next-step name resolution.
     */
    private val executableOps by lazy {
        definition.operators.filter { it is StepDef<*, *> || it is FilterDef<*> }
    }

    /**
     * The [KType] of the source payload, derived from the first executable operator's input type.
     * Used by the ingestion loop when serializing polled records.
     */
    private val sourcePayloadType: KType by lazy {
        when (val first = executableOps.firstOrNull()) {
            is StepDef<*, *> -> first.inputType
            is FilterDef<*> -> first.inputType
            else -> error("Pipeline '${definition.name}' has no executable operators")
        }
    }

    /**
     * Starts the runtime: obtains or creates the long-lived run, reclaims any expired leases,
     * then launches the ingestion loop, worker loops, and watchdog loop.
     */
    suspend fun start() {
        val run = store.getOrCreateRun(definition.name, planVersion)
        runId = run.id
        store.reclaimExpiredLeases(Clock.System.now(), limit = Int.MAX_VALUE)
        launchIngestionLoop()
        launchWorkerLoops()
        launchWatchdogLoop()
    }

    /**
     * Stops all runtime loops by cancelling their [Job]s and suspending until they complete.
     *
     * Callers must `suspend` (or be inside a coroutine) to call this. After this function returns,
     * all loop coroutines have fully terminated — required for clean shutdown in tests and in
     * production (Phase 6 graceful shutdown builds on this).
     */
    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        jobs.clear()
    }

    // ── Ingestion loop ─────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun launchIngestionLoop() {
        val firstStepName = executableOps.firstOrNull()?.name ?: return
        val adapter = definition.source.adapter as SourceAdapter<Any?>
        val inMemoryStore =
            store as? InMemoryStore
                ?: error("PipelineRuntime requires InMemoryStore in Phase 1 (Phase 3 adds PostgresStore)")

        jobs +=
            scope.launch {
                while (isActive) {
                    val nonTerminal = store.countNonTerminal(runId)
                    if (nonTerminal < definition.maxInFlight) {
                        val maxPoll = definition.maxInFlight - nonTerminal
                        val records = adapter.poll(maxPoll)
                        if (records.isNotEmpty()) {
                            val ingress: List<IngressRecord<*>> =
                                records.map { record ->
                                    IngressRecord(
                                        sourceId = record.id,
                                        payload = serializer.serialize(record.payload, sourcePayloadType),
                                    )
                                }
                            inMemoryStore.appendIngress(runId, ingress, firstStep = firstStepName)
                            adapter.ack(records)
                        }
                    }
                    delay(workerPollInterval)
                }
            }
    }

    // ── Worker loops ───────────────────────────────────────────────────────────

    /**
     * Maps each executable operator to an [ExecutableOp] adapter and launches one worker loop per
     * operator. New operator types only need a new [ExecutableOp] implementation; the launch loop
     * itself is not modified.
     */
    @Suppress("UNCHECKED_CAST")
    private fun launchWorkerLoops() {
        executableOps.forEachIndexed { index, op ->
            val nextStepName = executableOps.getOrNull(index + 1)?.name
            val exe: ExecutableOp? =
                when (op) {
                    is StepDef<*, *> -> StepExecutable(op as StepDef<Any?, Any?>)
                    is FilterDef<*> -> FilterExecutable(op as FilterDef<Any?>)
                    else -> null // SourceDef is not in executableOps; no other operator types
                }
            exe?.let { launchWorker(it, nextStepName) }
        }
    }

    /**
     * Launches a single worker coroutine for the given [ExecutableOp].
     *
     * The loop claims up to [workerClaimLimit] items per cycle, executes and checkpoints each,
     * then delays by [workerPollInterval] before the next cycle. Operator-specific logic is fully
     * encapsulated in [ExecutableOp.executeAndCheckpoint]; the loop itself is operator-agnostic.
     */
    private fun launchWorker(
        exe: ExecutableOp,
        nextStepName: String?,
    ) {
        jobs +=
            scope.launch {
                while (isActive) {
                    val claimed = store.claim(exe.stepName, runId, workerClaimLimit, leaseDuration, workerId)
                    for (item in claimed) {
                        exe.executeAndCheckpoint(item, nextStepName)
                    }
                    delay(workerPollInterval)
                }
            }
    }

    // ── Operator executors ─────────────────────────────────────────────────────

    /**
     * Internal abstraction for an executable operator: encapsulates the step name used for
     * [DurableStore.claim] and the operator-specific execute-and-checkpoint logic.
     *
     * Adding a new operator type (e.g. `MapDef`) only requires a new implementation of this
     * interface; the worker launch loop ([launchWorker]) is not modified.
     */
    private sealed interface ExecutableOp {
        /** Step name used for [DurableStore.claim]. */
        val stepName: String

        /**
         * Deserializes the item payload, executes the operator function, and checkpoints the result.
         *
         * @param item The claimed work item.
         * @param nextStepName Next step name, or null if this is the final operator.
         */
        suspend fun executeAndCheckpoint(
            item: WorkItem,
            nextStepName: String?,
        )
    }

    /**
     * [ExecutableOp] for [StepDef]: runs the step function and checkpoints success (with serialized
     * output) or delegates failure to [handleFailure].
     */
    private inner class StepExecutable(
        private val def: StepDef<Any?, Any?>,
    ) : ExecutableOp {
        override val stepName: String = def.name

        override suspend fun executeAndCheckpoint(
            item: WorkItem,
            nextStepName: String?,
        ) {
            val result =
                runStepFn<Any?, Any?>(item, def.name, def.inputType) { input ->
                    def.fn(input)
                }
            result.fold(
                ifLeft = { failure -> handleFailure(item, failure, def) },
                ifRight = { output ->
                    val outJson = serializer.serialize(output!!, def.outputType)
                    store.checkpointSuccess(item, outJson, nextStepName)
                },
            )
        }
    }

    /**
     * [ExecutableOp] for [FilterDef]: runs the filter predicate and checkpoints filtered (false
     * result or [ItemFailure.isFiltered]), success (true result, payload forwarded unchanged), or
     * delegates non-filter failures to [handleFailure].
     */
    private inner class FilterExecutable(
        private val def: FilterDef<Any?>,
    ) : ExecutableOp {
        override val stepName: String = def.name

        override suspend fun executeAndCheckpoint(
            item: WorkItem,
            nextStepName: String?,
        ) {
            val result =
                runStepFn<Any?, Boolean>(item, def.name, def.inputType) { input ->
                    def.predicate(input)
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
                        store.checkpointSuccess(item, item.payloadJson!!, nextStepName)
                    } else {
                        store.checkpointFiltered(item, def.filteredReason.name)
                    }
                },
            )
        }
    }

    // ── Watchdog loop ──────────────────────────────────────────────────────────

    private fun launchWatchdogLoop() {
        jobs +=
            scope.launch {
                while (isActive) {
                    delay(watchdogInterval)
                    store.reclaimExpiredLeases(Clock.System.now(), limit = 100)
                }
            }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Shared Arrow + context helper used by all [ExecutableOp] implementations.
     *
     * Deserializes [item]'s `payloadJson` to [I] using [inputType], constructs a [StepCtx],
     * then executes [block] inside an `either {}` scope with the ctx in context. The resulting
     * [Either] carries an [ItemFailure] on the left or the block's return value on the right.
     *
     * This eliminates the repeated deserialize → buildCtx → `either { with(ctx) { ... } }` pattern
     * that would otherwise appear in every operator's execute function.
     *
     * @param item The work item being executed; must have a non-null `payloadJson`.
     * @param stepName Name of the step, used for [StepCtx.stepName].
     * @param inputType [KType] of [I] used for deserialization.
     * @param block The operator function to execute in the Arrow + StepCtx context.
     * @return [Either.Left] with the raised [ItemFailure], or [Either.Right] with the result.
     */
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
            pipelineName = definition.name,
            runId = runId,
            itemId = item.id,
            itemKey = item.sourceId,
            stepName = stepName,
            attempt = item.attemptCount + 1,
            startedAt = Clock.System.now().toEpochMilliseconds(),
        )

    /**
     * Handles a step failure by scheduling a retry or marking the item as permanently failed.
     *
     * If [stepDef] has a [io.github.fpaschos.pipekt.core.RetryPolicy] and attempts remain,
     * [failure] is retryable, and backoff is configured, a `retryAt` instant is computed and the
     * item is checkpointed as PENDING. Otherwise the item is checkpointed as FAILED.
     *
     * Passing `stepDef = null` (e.g. from a filter failure that is not [ItemFailure.isFiltered])
     * always results in terminal FAILED — no retry.
     */
    private suspend fun handleFailure(
        item: WorkItem,
        failure: ItemFailure,
        stepDef: StepDef<*, *>?,
    ) {
        if (failure.isRetryable() && stepDef != null) {
            val policy = stepDef.retryPolicy
            val attemptsUsed = item.attemptCount + 1
            if (attemptsUsed < policy.maxAttempts) {
                val retryAt = Clock.System.now() + policy.backoffMs.milliseconds
                store.checkpointFailure(item, failure.message, retryAt)
                return
            }
        }
        store.checkpointFailure(item, failure.message, retryAt = null)
    }
}

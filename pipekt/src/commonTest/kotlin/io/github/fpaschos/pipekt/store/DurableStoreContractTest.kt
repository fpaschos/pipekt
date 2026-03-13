package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlin.time.Duration

/**
 * Contract coverage for the [DurableStore] SPI surface.
 *
 * This test uses a minimal in-memory stub implementing every method from the
 * "Durable Store" contract in `plans/streams-contracts-v1.md`, and invokes
 * each one once to verify that the interface is complete, callable, and
 * returns values of the expected types without throwing.
 */
class DurableStoreContractTest :
    FunSpec({

        test("durableStoreStubImplementsAllMethodsAndIsCallable") {
            val store =
                object : DurableStore {
                    override suspend fun getOrCreateRun(
                        pipeline: String,
                        planVersion: String,
                        now: Instant,
                    ): RunRecord =
                        RunRecord(
                            id = "run-1",
                            pipeline = pipeline,
                            planVersion = planVersion,
                            status = "ACTIVE",
                            createdAt = now,
                            updatedAt = now,
                        )

                    override suspend fun getRun(runId: String): RunRecord? = null

                    override suspend fun listActiveRuns(pipeline: String): List<RunRecord> = emptyList()

                    override suspend fun appendIngress(
                        runId: String,
                        records: List<IngressRecord<*>>,
                        now: Instant,
                    ): AppendIngressResult = AppendIngressResult(appended = 0, duplicates = 0)

                    override suspend fun claim(
                        step: String,
                        runId: String,
                        limit: Int,
                        leaseDuration: Duration,
                        workerId: String,
                        now: Instant,
                    ): List<WorkItem> = emptyList()

                    override suspend fun checkpointSuccess(
                        item: WorkItem,
                        outputJson: String,
                        nextStep: String?,
                        now: Instant,
                    ) = Unit

                    override suspend fun checkpointFiltered(
                        item: WorkItem,
                        reason: String,
                        now: Instant,
                    ) = Unit

                    override suspend fun checkpointFailure(
                        item: WorkItem,
                        errorJson: String,
                        retryAt: Instant?,
                        now: Instant,
                    ) = Unit

                    override suspend fun countNonTerminal(runId: String): Int = 0

                    override suspend fun reclaimExpiredLeases(
                        now: Instant,
                        limit: Int,
                    ): List<WorkItem> = emptyList()
                }

            val t1 = Instant.fromEpochMilliseconds(1000L)
            val t2 = Instant.fromEpochMilliseconds(2000L)
            val t3 = Instant.fromEpochMilliseconds(3000L)

            val run = store.getOrCreateRun("p", "v1", t1)
            run.pipeline.shouldBe("p")
            store.getRun("run-1") shouldBe null
            store.listActiveRuns("p") shouldBe emptyList()
            val appendResult = store.appendIngress("run-1", emptyList(), t1)
            appendResult.appended.shouldBe(0)
            appendResult.duplicates.shouldBe(0)
            store.claim("step1", "run-1", 10, Duration.parse("5s"), "worker-1", t1) shouldBe emptyList()
            val item =
                WorkItem(
                    id = "i1",
                    runId = "run-1",
                    sourceId = "s1",
                    currentStep = "step1",
                    status = WorkItemStatus.PENDING,
                    payloadJson = "{}",
                    lastErrorJson = null,
                    attemptCount = 0,
                    leaseOwner = null,
                    leaseExpiry = null,
                    retryAt = null,
                    createdAt = t1,
                    updatedAt = t1,
                )
            store.checkpointSuccess(item, "{}", "step2", t2)
            store.checkpointFiltered(item, "reason", t2)
            store.checkpointFailure(item, "err", null, t2)
            store.countNonTerminal("run-1").shouldBe(0)
            store.reclaimExpiredLeases(t3, 10) shouldBe emptyList()
        }
    })

package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * Contract test: [DurableStore] interface is complete and callable.
 * A minimal stub implements every method; one test invokes each to verify the SPI shape.
 */
class DurableStoreContractTest :
    FunSpec({

        test("durableStoreStubImplementsAllMethodsAndIsCallable") {
            val store =
                object : DurableStore {
                    override suspend fun getOrCreateRun(
                        pipeline: String,
                        planVersion: String,
                        nowMs: Long,
                    ): RunRecord =
                        RunRecord(
                            id = "run-1",
                            pipeline = pipeline,
                            planVersion = planVersion,
                            status = "ACTIVE",
                            createdAtMs = nowMs,
                            updatedAtMs = nowMs,
                        )

                    override suspend fun getRun(runId: String): RunRecord? = null

                    override suspend fun listActiveRuns(pipeline: String): List<RunRecord> = emptyList()

                    override suspend fun appendIngress(
                        runId: String,
                        records: List<IngressRecord<*>>,
                        nowMs: Long,
                    ): AppendIngressResult = AppendIngressResult(appended = 0, duplicates = 0)

                    override suspend fun claim(
                        step: String,
                        runId: String,
                        limit: Int,
                        leaseMs: Long,
                        workerId: String,
                    ): List<WorkItem> = emptyList()

                    override suspend fun checkpointSuccess(
                        item: WorkItem,
                        outputJson: String,
                        nextStep: String?,
                        nowMs: Long,
                    ) = Unit

                    override suspend fun checkpointFiltered(
                        item: WorkItem,
                        reason: String,
                        nowMs: Long,
                    ) = Unit

                    override suspend fun checkpointFailure(
                        item: WorkItem,
                        errorJson: String,
                        retryAtEpochMs: Long?,
                        nowMs: Long,
                    ) = Unit

                    override suspend fun countNonTerminal(runId: String): Int = 0

                    override suspend fun reclaimExpiredLeases(
                        nowEpochMs: Long,
                        limit: Int,
                    ): List<WorkItem> = emptyList()
                }

            val run =
                store.getOrCreateRun("p", "v1", 1000L)
            run.pipeline.shouldBe("p")
            store.getRun("run-1") shouldBe null
            store.listActiveRuns("p") shouldBe emptyList()
            val appendResult =
                store.appendIngress("run-1", emptyList(), 1000L)
            appendResult.appended.shouldBe(0)
            appendResult.duplicates.shouldBe(0)
            store.claim("step1", "run-1", 10, 5000L, "worker-1") shouldBe emptyList()
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
                    leaseExpiryMs = null,
                    retryAtMs = null,
                    createdAtMs = 1000L,
                    updatedAtMs = 1000L,
                )
            store.checkpointSuccess(item, "{}", "step2", 2000L)
            store.checkpointFiltered(item, "reason", 2000L)
            store.checkpointFailure(item, "err", null, 2000L)
            store.countNonTerminal("run-1").shouldBe(0)
            store.reclaimExpiredLeases(3000L, 10) shouldBe emptyList()
        }
    })

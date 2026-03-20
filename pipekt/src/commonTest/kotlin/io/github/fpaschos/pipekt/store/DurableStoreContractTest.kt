package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Contract coverage for the [DurableStore] SPI surface.
 *
 * This test uses a minimal in-memory stub implementing every method from the
 * "Durable Store" contract in `specs/streams-contracts-v1.md`, and invokes
 * each one once to verify that the interface is complete, callable, and
 * returns values of the expected types without throwing.
 *
 * Behavioral contract coverage (state transitions, idempotency, lease semantics) is
 * the responsibility of `InMemoryStoreContractTest` (Phase 1C).
 */
class DurableStoreContractTest :
    FunSpec({

        test("durable store stub implements all interface methods and returns expected types") {
            val t1 = Instant.fromEpochMilliseconds(1000L)

            val store =
                object : DurableStore {
                    override suspend fun findOrCreateRun(
                        pipeline: String,
                        planVersion: String,
                    ): RunRecord =
                        RunRecord(
                            id = "run-1",
                            pipeline = pipeline,
                            planVersion = planVersion,
                            status = "ACTIVE",
                            createdAt = t1,
                            updatedAt = t1,
                        )

                    override suspend fun findRun(runId: String): RunRecord? = null

                    override suspend fun findAllActiveRuns(pipeline: String): List<RunRecord> = emptyList()

                    override suspend fun appendIngress(
                        runId: String,
                        records: List<IngressRecord<*>>,
                        firstStep: String,
                    ): AppendIngressResult = AppendIngressResult(appended = 0, duplicates = 0)

                    override suspend fun claim(
                        step: String,
                        runId: String,
                        limit: Int,
                        leaseDuration: Duration,
                        workerId: String,
                    ): List<WorkItem> = emptyList()

                    override suspend fun checkpointSuccess(
                        item: WorkItem,
                        outputJson: String,
                        nextStep: String?,
                    ) = Unit

                    override suspend fun checkpointFiltered(
                        item: WorkItem,
                        reason: String,
                    ) = Unit

                    override suspend fun checkpointFailure(
                        item: WorkItem,
                        errorJson: String,
                        retryAt: Instant?,
                    ) = Unit

                    override suspend fun countNonTerminal(runId: String): Int = 0

                    override suspend fun reclaimExpiredLeases(
                        now: Instant,
                        limit: Int,
                    ): List<WorkItem> = emptyList()
                }

            val t3 = Instant.fromEpochMilliseconds(3000L)

            val run = store.findOrCreateRun("p", "v1")
            assertSoftly(run) {
                pipeline shouldBe "p"
            }
            store.findRun("run-1") shouldBe null
            store.findAllActiveRuns("p") shouldBe emptyList()
            val appendResult = store.appendIngress("run-1", emptyList())
            assertSoftly(appendResult) {
                appended shouldBe 0
                duplicates shouldBe 0
            }
            store.claim("step1", "run-1", 10, Duration.parse("5s"), "worker-1") shouldBe emptyList()
            val item =
                WorkItem(
                    id = "i1",
                    runId = "run-1",
                    sourceId = "s1",
                    currentStep = "step1",
                    status = WorkItemStatus.PENDING,
                    payloadJson = "{}",
                    attemptCount = 0,
                    leaseOwner = null,
                    leaseExpiry = null,
                    retryAt = null,
                    createdAt = t1,
                    updatedAt = t1,
                )
            store.checkpointSuccess(item, "{}", "step2")
            store.checkpointFiltered(item, "reason")
            store.checkpointFailure(item, "err", null)
            store.countNonTerminal("run-1").shouldBe(0)
            store.reclaimExpiredLeases(t3, 10) shouldBe emptyList()
        }
    })

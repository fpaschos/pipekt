package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Contract: WorkItem carries all required fields per streams-contracts-v1 (id, runId, sourceId,
 * currentStep, status, payloadJson, lastErrorJson, attemptCount, leaseOwner, leaseExpiryMs,
 * retryAtMs, createdAtMs, updatedAtMs).
 */
class WorkItemTest :
    FunSpec({

        test("workItemHasRequiredFields") {
            val w =
                WorkItem(
                    id = "item-1",
                    runId = "run-1",
                    sourceId = "src-1",
                    currentStep = "step1",
                    status = WorkItemStatus.PENDING,
                    payloadJson = """{"x":1}""",
                    lastErrorJson = null,
                    attemptCount = 0,
                    leaseOwner = null,
                    leaseExpiryMs = null,
                    retryAtMs = null,
                    createdAtMs = 1000L,
                    updatedAtMs = 2000L,
                )
            w.id.shouldBe("item-1")
            w.runId.shouldBe("run-1")
            w.sourceId.shouldBe("src-1")
            w.currentStep.shouldBe("step1")
            w.status.shouldBe(WorkItemStatus.PENDING)
            w.payloadJson.shouldBe("""{"x":1}""")
            w.lastErrorJson.shouldBe(null)
            w.attemptCount.shouldBe(0)
            w.leaseOwner.shouldBe(null)
            w.leaseExpiryMs.shouldBe(null)
            w.retryAtMs.shouldBe(null)
            w.createdAtMs.shouldBe(1000L)
            w.updatedAtMs.shouldBe(2000L)
        }
    })

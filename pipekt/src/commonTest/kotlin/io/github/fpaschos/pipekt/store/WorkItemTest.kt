package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Contract coverage for [WorkItem] as a persistent entity.
 *
 * These tests validate the "WorkItem minimum fields" (and related fields)
 * contract from `plans/streams-contracts-v1.md`: every work item must carry
 * identity, run, step, status, payload and error metadata, lease information,
 * retry timing, and creation / update timestamps, all exposed as immutable
 * value-type fields.
 */
class WorkItemTest :
    FunSpec({

        test("work item exposes all required fields") {
            val createdAt = Instant.fromEpochMilliseconds(1000L)
            val updatedAt = Instant.fromEpochMilliseconds(2000L)
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
                    leaseExpiry = null,
                    retryAt = null,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
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
            w.leaseExpiry.shouldBe(null)
            w.retryAt.shouldBe(null)
            w.createdAt.shouldBe(createdAt)
            w.updatedAt.shouldBe(updatedAt)
        }
    })

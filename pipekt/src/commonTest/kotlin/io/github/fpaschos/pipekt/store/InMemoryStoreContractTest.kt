package io.github.fpaschos.pipekt.store

import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Contract tests for [InMemoryStore] covering all [DurableStore] behavioral requirements.
 *
 * Each test targets a single state transition or contract invariant from
 * `plans/streams-contracts-v1.md` (Store Contracts section). Coverage:
 *
 * - Run lifecycle: getOrCreateRun idempotency, planVersion keying, listActiveRuns filtering
 * - Ingress: appendIngress counts, duplicate suppression by (runId, sourceId)
 * - Claim: lease assignment, skip of already-leased items
 * - Checkpoints: success (advance and terminal), filtered (FILTERED + null payload), failure (FAILED and retryable PENDING)
 * - Backpressure: countNonTerminal counts only PENDING + IN_PROGRESS
 * - Reclaim: reclaimExpiredLeases resets expired IN_PROGRESS back to PENDING
 *
 * Tests use pre-serialized [IngressRecord]<[String]> payloads; serialization is the runtime's
 * responsibility (Phase 1D), not the store's.
 */
@OptIn(ExperimentalUuidApi::class)
class InMemoryStoreContractTest :
    FunSpec({

        /** Returns a minimal [IngressRecord]<[String]> with the given sourceId and a JSON payload. */
        fun record(
            sourceId: String,
            payload: String = "\"payload\"",
        ) = IngressRecord(id = Uuid.random(), sourceId = sourceId, payload = payload)

        test("getOrCreateRun creates a new run and returns it") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            run.pipeline shouldBe "pipe1"
            run.planVersion shouldBe "v1"
            run.status shouldBe RunRecord.STATUS_ACTIVE
            run.id.shouldNotBeNull()
        }

        test("getOrCreateRun with same pipeline and planVersion returns the same run") {
            val store = InMemoryStore()
            val first = store.getOrCreateRun("pipe1", "v1")
            val second = store.getOrCreateRun("pipe1", "v1")
            second.id shouldBe first.id
        }

        test("getOrCreateRun with bumped planVersion creates a second distinct run") {
            val store = InMemoryStore()
            val v1 = store.getOrCreateRun("pipe1", "v1")
            val v2 = store.getOrCreateRun("pipe1", "v2")
            v2.id shouldNotBe v1.id
            v2.planVersion shouldBe "v2"
        }

        test("listActiveRuns excludes STATUS_FAILED runs") {
            val store = InMemoryStore()
            store.getOrCreateRun("pipe1", "v1")
            store.getOrCreateRun("pipe1", "v2")
            store.markRunFailed("pipe1", "v1")

            val active = store.listActiveRuns("pipe1")
            active shouldHaveSize 1
            active.first().planVersion shouldBe "v2"
        }

        test("appendIngress appended count matches number of unique records") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            val result = store.appendIngress(run.id, listOf(record("s1"), record("s2"), record("s3")), firstStep = "step1")
            result.appended shouldBe 3
            result.duplicates shouldBe 0
        }

        test("appendIngress skips duplicates for same runId and sourceId") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1"), record("s2")), firstStep = "step1")
            val result = store.appendIngress(run.id, listOf(record("s2"), record("s3")), firstStep = "step1")
            result.appended shouldBe 1
            result.duplicates shouldBe 1
        }

        test("claim returns PENDING items and sets them IN_PROGRESS with lease") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1"), record("s2")), firstStep = "step1")

            val claimed = store.claim("step1", run.id, 10, 30.seconds, "worker-1")
            claimed shouldHaveSize 2
            claimed.forEach { item ->
                item.status shouldBe WorkItemStatus.IN_PROGRESS
                item.leaseOwner shouldBe "worker-1"
                item.leaseExpiry.shouldNotBeNull()
            }
        }

        test("claim does not return items already IN_PROGRESS with a valid lease") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1")), firstStep = "step1")

            store.claim("step1", run.id, 10, 30.seconds, "worker-1")
            val secondClaim = store.claim("step1", run.id, 10, 30.seconds, "worker-2")
            secondClaim shouldHaveSize 0
        }

        test("checkpointSuccess with nextStep advances item to next step as PENDING with new payload") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1", "\"input\"")), firstStep = "step1")
            val item = store.claim("step1", run.id, 1, 30.seconds, "worker-1").first()

            store.checkpointSuccess(item, "\"output\"", nextStep = "step2")

            store.getRun(run.id).shouldNotBeNull()
            val workItem = store.getWorkItem(item.id).shouldNotBeNull()
            workItem.currentStep shouldBe "step2"
            workItem.status shouldBe WorkItemStatus.PENDING
            workItem.payloadJson shouldBe "\"output\""
            workItem.attemptCount shouldBe 1
        }

        test("checkpointSuccess with nextStep null marks item COMPLETED and nulls payloadJson") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1")), firstStep = "step1")
            val item = store.claim("step1", run.id, 1, 30.seconds, "worker-1").first()

            store.checkpointSuccess(item, "\"output\"", nextStep = null)

            val workItem = store.getWorkItem(item.id).shouldNotBeNull()
            workItem.status shouldBe WorkItemStatus.COMPLETED
            workItem.payloadJson.shouldBeNull()
            workItem.attemptCount shouldBe 1
        }

        test("checkpointFiltered marks item FILTERED and nulls payloadJson") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1")), firstStep = "step1")
            val item = store.claim("step1", run.id, 1, 30.seconds, "worker-1").first()

            store.checkpointFiltered(item, "BELOW_THRESHOLD")

            val workItem = store.getWorkItem(item.id).shouldNotBeNull()
            workItem.status shouldBe WorkItemStatus.FILTERED
            workItem.payloadJson.shouldBeNull()
            workItem.lastErrorJson shouldBe "BELOW_THRESHOLD"
            workItem.attemptCount shouldBe 1
        }

        test("checkpointFailure with retryAt null marks item FAILED and nulls payloadJson") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1")), firstStep = "step1")
            val item = store.claim("step1", run.id, 1, 30.seconds, "worker-1").first()

            store.checkpointFailure(item, "fatal error", retryAt = null)

            val workItem = store.getWorkItem(item.id).shouldNotBeNull()
            workItem.status shouldBe WorkItemStatus.FAILED
            workItem.payloadJson.shouldBeNull()
            workItem.lastErrorJson shouldBe "fatal error"
            workItem.attemptCount shouldBe 1
        }

        test("checkpointFailure with retryAt keeps item PENDING with retryAt set") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1")), firstStep = "step1")
            val item = store.claim("step1", run.id, 1, 30.seconds, "worker-1").first()
            val retryAt = Clock.System.now() + 5.seconds

            store.checkpointFailure(item, "transient error", retryAt = retryAt)

            val workItem = store.getWorkItem(item.id).shouldNotBeNull()
            workItem.status shouldBe WorkItemStatus.PENDING
            workItem.retryAt shouldBe retryAt
            workItem.lastErrorJson shouldBe "transient error"
            workItem.payloadJson.shouldNotBeNull()
            workItem.attemptCount shouldBe 1
        }

        test("countNonTerminal counts PENDING and IN_PROGRESS items but not terminal items") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1"), record("s2"), record("s3")), firstStep = "step1")

            // Claim and complete one item
            val claimed = store.claim("step1", run.id, 1, 30.seconds, "worker-1")
            store.checkpointSuccess(claimed.first(), "\"out\"", nextStep = null)

            // 2 PENDING + 0 IN_PROGRESS = 2 non-terminal
            store.countNonTerminal(run.id) shouldBe 2
        }

        test("reclaimExpiredLeases resets expired IN_PROGRESS items back to PENDING") {
            val store = InMemoryStore()
            val run = store.getOrCreateRun("pipe1", "v1")
            store.appendIngress(run.id, listOf(record("s1")), firstStep = "step1")

            // Claim with a short lease duration that has already expired by using a past expiry directly
            val item = store.claim("step1", run.id, 1, 1.seconds, "worker-1").first()
            item.status shouldBe WorkItemStatus.IN_PROGRESS

            // Reclaim by passing a now that is past the lease expiry
            val futureNow = item.leaseExpiry!! + 1.seconds
            val reclaimed = store.reclaimExpiredLeases(futureNow, limit = 10)

            reclaimed shouldHaveSize 1
            val reclaimedItem = store.getWorkItem(item.id).shouldNotBeNull()
            reclaimedItem.status shouldBe WorkItemStatus.PENDING
            reclaimedItem.leaseOwner.shouldBeNull()
            reclaimedItem.leaseExpiry.shouldBeNull()
        }
    })

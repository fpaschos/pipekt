package io.github.fpaschos.pipekt.runtime

import io.github.fpaschos.pipekt.core.FakeSourceAdapter
import io.github.fpaschos.pipekt.core.KotlinxPayloadSerializer
import io.github.fpaschos.pipekt.core.SourceRecord
import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.github.fpaschos.pipekt.core.pipeline
import io.github.fpaschos.pipekt.store.InMemoryStore
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Happy-path acceptance tests for [PipelineRuntime].
 *
 * Each test builds a pipeline via the DSL, loads a [FakeSourceAdapter] with pre-seeded records,
 * starts the runtime in a [kotlinx.coroutines.test.TestScope], advances virtual time by a fixed
 * budget sufficient for all items to be ingested and processed, stops the runtime, then asserts
 * final [io.github.fpaschos.pipekt.store.WorkItem] states.
 *
 * **Why `advanceTimeBy` → `stop()` → `advanceUntilIdle`:** The runtime loops run `while (isActive)`,
 * so `advanceUntilIdle()` alone never returns (loops re-enqueue `delay()` indefinitely).
 * Instead: (1) `advanceTimeBy(1.seconds)` unblocks all `delay()` calls in the virtual window so
 * all items complete in the first few ticks; (2) `runtime.stop()` cancels and joins loop jobs;
 * (3) `advanceUntilIdle()` drains cancellation continuations so `runTest` exits cleanly.
 *
 * Coverage (from `plans/streams-delivery-phases.md` Phase 1D exit criteria):
 * - Single-step pipeline: all items reach COMPLETED
 * - Two-step pipeline: items advance through both steps and reach COMPLETED
 * - Filter step: items failing the predicate reach FILTERED with null payloadJson
 * - Duplicate ingress: same sourceId fed twice results in exactly one WorkItem
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineRuntimeHappyPathTest :
    FunSpec({

        /** Simple payload type used across all tests. */
        @Serializable
        data class Msg(
            val value: String,
        )

        val serializer = KotlinxPayloadSerializer

        test("single step pipeline: all ingested items reach COMPLETED") {
            val store = InMemoryStore()
            val adapter =
                FakeSourceAdapter(
                    listOf(
                        SourceRecord("s1", Msg("hello")),
                        SourceRecord("s2", Msg("world")),
                    ),
                )
            val definition =
                pipeline<Msg>("test-pipeline", maxInFlight = 100) {
                    source("src", adapter)
                    step<Msg, Msg>("step1") { msg -> Msg(msg.value.uppercase()) }
                }.shouldBeRight()

            runTest(StandardTestDispatcher()) {
                val runtime =
                    PipelineRuntime(
                        pipeline = definition,
                        store = store,
                        serializer = serializer,
                        scope = this,
                        workerPollInterval = 1.milliseconds,
                        watchdogInterval = 5.milliseconds,
                    )
                runtime.start()
                advanceTimeBy(1.seconds)
                runtime.stop()
                advanceUntilIdle()
            }

            val run = store.findAllActiveRuns("test-pipeline").first()
            store.countNonTerminal(run.id) shouldBe 0
            assertSoftly(store.getWorkItemBySourceId(run.id, "s1")!!) {
                status shouldBe WorkItemStatus.COMPLETED
                payloadJson.shouldBeNull()
            }
            assertSoftly(store.getWorkItemBySourceId(run.id, "s2")!!) {
                status shouldBe WorkItemStatus.COMPLETED
                payloadJson.shouldBeNull()
            }
        }

        test("two-step pipeline: items advance through both steps and reach COMPLETED") {
            val store = InMemoryStore()
            val adapter =
                FakeSourceAdapter(
                    listOf(SourceRecord("s1", Msg("ping"))),
                )
            val definition =
                pipeline<Msg>("two-step-pipe", maxInFlight = 100) {
                    source("src", adapter)
                    step<Msg, Msg>("step1") { msg -> Msg(msg.value + "-step1") }
                    step<Msg, Msg>("step2") { msg -> Msg(msg.value + "-step2") }
                }.shouldBeRight()

            runTest(StandardTestDispatcher()) {
                val runtime =
                    PipelineRuntime(
                        pipeline = definition,
                        store = store,
                        serializer = serializer,
                        scope = this,
                        workerPollInterval = 1.milliseconds,
                        watchdogInterval = 5.milliseconds,
                    )
                runtime.start()
                advanceTimeBy(1.seconds)
                runtime.stop()
                advanceUntilIdle()
            }

            val run = store.findAllActiveRuns("two-step-pipe").first()
            store.countNonTerminal(run.id) shouldBe 0
            val item = store.getWorkItemBySourceId(run.id, "s1")!!
            assertSoftly(item) {
                status shouldBe WorkItemStatus.COMPLETED
                payloadJson.shouldBeNull()
            }
        }

        test("filter step: items failing the predicate reach FILTERED with null payloadJson") {
            val store = InMemoryStore()
            val adapter =
                FakeSourceAdapter(
                    listOf(
                        SourceRecord("s1", Msg("keep")),
                        SourceRecord("s2", Msg("drop")),
                    ),
                )
            val definition =
                pipeline<Msg>("filter-pipe", maxInFlight = 100) {
                    source("src", adapter)
                    filter<Msg>("filter1") { msg -> msg.value == "keep" }
                    step<Msg, Msg>("step1") { msg -> msg }
                }.getOrNull()!!

            runTest(StandardTestDispatcher()) {
                val runtime =
                    PipelineRuntime(
                        pipeline = definition,
                        store = store,
                        serializer = serializer,
                        scope = this,
                        workerPollInterval = 1.milliseconds,
                        watchdogInterval = 5.milliseconds,
                    )
                runtime.start()
                advanceTimeBy(1.seconds)
                runtime.stop()
                advanceUntilIdle()
            }

            val run = store.findAllActiveRuns("filter-pipe").first()
            assertSoftly(store.getWorkItemBySourceId(run.id, "s1")!!) {
                status shouldBe WorkItemStatus.COMPLETED
            }
            val dropped = store.getWorkItemBySourceId(run.id, "s2")!!
            assertSoftly(dropped) {
                status shouldBe WorkItemStatus.FILTERED
                payloadJson.shouldBeNull()
            }
        }

        test("duplicate ingress: same sourceId fed twice results in exactly one WorkItem") {
            val store = InMemoryStore()
            val adapter =
                FakeSourceAdapter(
                    listOf(
                        SourceRecord("s1", Msg("first")),
                        SourceRecord("s1", Msg("duplicate")),
                    ),
                )
            val definition =
                pipeline<Msg>("dedup-pipe", maxInFlight = 100) {
                    source("src", adapter)
                    step<Msg, Msg>("step1") { msg -> msg }
                }.getOrNull()!!

            runTest(StandardTestDispatcher()) {
                val runtime =
                    PipelineRuntime(
                        pipeline = definition,
                        store = store,
                        serializer = serializer,
                        scope = this,
                        workerPollInterval = 1.milliseconds,
                        watchdogInterval = 5.milliseconds,
                    )
                runtime.start()
                advanceTimeBy(1.seconds)
                runtime.stop()
                advanceUntilIdle()
            }

            val run = store.findAllActiveRuns("dedup-pipe").first()
            val allItems = store.getAllWorkItems(run.id)
            allItems shouldHaveSize 1
            assertSoftly(allItems.first()) {
                status shouldBe WorkItemStatus.COMPLETED
            }
        }
    })

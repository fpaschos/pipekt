package io.github.fpaschos.pipekt.runtime.new

import io.github.fpaschos.pipekt.core.FakeSourceAdapter
import io.github.fpaschos.pipekt.core.KotlinxPayloadSerializer
import io.github.fpaschos.pipekt.core.SourceRecord
import io.github.fpaschos.pipekt.core.WorkItemStatus
import io.github.fpaschos.pipekt.core.pipeline
import io.github.fpaschos.pipekt.store.InMemoryStore
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineDslRuntimeTest :
    FunSpec({
        test("pipeline DSL starts and processes items through the actor runtime") {
            @Serializable
            data class Msg(
                val value: String,
            )

            val store = InMemoryStore()
            val adapter =
                FakeSourceAdapter(
                    listOf(
                        SourceRecord("s1", Msg("hello")),
                        SourceRecord("s2", Msg("world")),
                    ),
                )
            val definition =
                pipeline<Msg>("dsl-runtime-pipeline", maxInFlight = 100) {
                    source("src", adapter)
                    step<Msg, Msg>("uppercase") { msg -> Msg(msg.value.uppercase()) }
                }.shouldBeRight()

            runTest(StandardTestDispatcher()) {
                val orchestrator =
                    createPipelineOrchestrator(
                        store = store,
                        serializer = KotlinxPayloadSerializer,
                    )

                val executable =
                    with(orchestrator) {
                        definition.start(
                            planVersion = "v1",
                            config =
                                RuntimeConfig(
                                    workerPollInterval = 1.milliseconds,
                                    watchdogInterval = 5.milliseconds,
                                    leaseDuration = 100.milliseconds,
                                    workerClaimLimit = 8,
                                ),
                        )
                    }

                advanceTimeBy(1.seconds)
                runCurrent()
                executable.stop()
                orchestrator.shutdown()
                advanceUntilIdle()
            }

            val runs = store.findAllActiveRuns("dsl-runtime-pipeline")
            runs shouldHaveSize 1
            val run = runs.single()
            store.countNonTerminal(run.id) shouldBe 0

            val items = store.getAllWorkItems(run.id)
            items shouldHaveSize 2
            items.map { it.sourceId } shouldContainExactlyInAnyOrder listOf("s1", "s2")

            assertSoftly(store.getWorkItemBySourceId(run.id, "s1")!!) {
                status shouldBe WorkItemStatus.COMPLETED
                payloadJson.shouldBeNull()
            }
            assertSoftly(store.getWorkItemBySourceId(run.id, "s2")!!) {
                status shouldBe WorkItemStatus.COMPLETED
                payloadJson.shouldBeNull()
            }

            adapter.ackedIds shouldContainExactlyInAnyOrder setOf("s1", "s2")
            adapter.nackedIds shouldBe emptySet()
        }
    })

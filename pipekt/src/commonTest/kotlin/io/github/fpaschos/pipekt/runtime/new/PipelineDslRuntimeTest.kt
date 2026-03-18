package io.github.fpaschos.pipekt.runtime.new

import arrow.core.raise.context.raise
import io.github.fpaschos.pipekt.core.CoreFailure
import io.github.fpaschos.pipekt.core.FakeSourceAdapter
import io.github.fpaschos.pipekt.core.KotlinxPayloadSerializer
import io.github.fpaschos.pipekt.core.RetryPolicy
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

        test("ingestion stops appending when slow workers hit maxInFlight and resumes afterwards") {
            @Serializable
            data class Msg(
                val value: String,
            )

            val store = InMemoryStore()
            val adapter =
                FakeSourceAdapter(
                    (1..5).map { index ->
                        SourceRecord("s$index", Msg("msg-$index"))
                    },
                )
            val definition =
                pipeline<Msg>("backpressure-pipeline", maxInFlight = 2) {
                    source("src", adapter)
                    step<Msg, Msg>("slow-step") { msg ->
                        delay(250.milliseconds)
                        Msg(msg.value.uppercase())
                    }
                }.shouldBeRight()

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
                                workerPollInterval = 10.milliseconds,
                                watchdogInterval = 25.milliseconds,
                                leaseDuration = 2.seconds,
                                workerClaimLimit = 8,
                            ),
                    )
                }

            try {
                waitUntil {
                    adapter.ackedIds.size == 2
                }

                val run = store.findAllActiveRuns("backpressure-pipeline").single()
                delay(100.milliseconds)

                adapter.ackedIds.size shouldBe 2
                store.getAllWorkItems(run.id).size shouldBe 2
                store.countNonTerminal(run.id) shouldBe 2

                waitUntil {
                    adapter.ackedIds.size == 5 && store.countNonTerminal(run.id) == 0
                }

                store.getAllWorkItems(run.id).size shouldBe 5
                store.getAllWorkItems(run.id).all { it.status == WorkItemStatus.COMPLETED } shouldBe true
            } finally {
                executable.stop()
                orchestrator.shutdown()
            }
        }

        test("retryable failures respect retry backoff before the next attempt") {
            @Serializable
            data class Msg(
                val value: String,
            )

            val store = InMemoryStore()
            val adapter = FakeSourceAdapter(listOf(SourceRecord("s1", Msg("hello"))))
            var attempts = 0
            val started = TimeSource.Monotonic.markNow()
            val attemptOffsetsMs = mutableListOf<Long>()

            val definition =
                pipeline<Msg>("retry-pipeline", maxInFlight = 1) {
                    source("src", adapter)
                    step<Msg, Msg>(
                        name = "retrying-step",
                        retryPolicy = RetryPolicy(maxAttempts = 3, backoffMs = 150),
                    ) { msg ->
                        attempts += 1
                        attemptOffsetsMs += started.elapsedNow().inWholeMilliseconds
                        if (attempts < 3) {
                            raise(CoreFailure.Retryable("try again", attempt = attempts))
                        }
                        Msg(msg.value.uppercase())
                    }
                }.shouldBeRight()

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
                                workerPollInterval = 10.milliseconds,
                                watchdogInterval = 25.milliseconds,
                                leaseDuration = 1.seconds,
                                workerClaimLimit = 8,
                            ),
                    )
                }

            try {
                waitUntil {
                    attempts == 3
                }

                val run = store.findAllActiveRuns("retry-pipeline").single()
                waitUntil {
                    store.countNonTerminal(run.id) == 0
                }

                val item = store.getWorkItemBySourceId(run.id, "s1")!!
                item.status shouldBe WorkItemStatus.COMPLETED
                item.attemptCount shouldBe 3
                attemptOffsetsMs.size shouldBe 3
                (attemptOffsetsMs[1] - attemptOffsetsMs[0] >= 120L) shouldBe true
                (attemptOffsetsMs[2] - attemptOffsetsMs[1] >= 120L) shouldBe true
            } finally {
                executable.stop()
                orchestrator.shutdown()
            }
        }

        test("watchdog reclaims expired in-progress items and workers resume them") {
            @Serializable
            data class Msg(
                val value: String,
            )

            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<Msg>(emptyList())
            val definition =
                pipeline<Msg>("watchdog-pipeline", maxInFlight = 10) {
                    source("src", adapter)
                    step<Msg, Msg>("resume-step") { msg ->
                        Msg(msg.value.uppercase())
                    }
                }.shouldBeRight()

            val run = store.findOrCreateRun("watchdog-pipeline", "v1")
            store.appendIngress(
                runId = run.id,
                records =
                    listOf(
                        io.github.fpaschos.pipekt.core
                            .IngressRecord(sourceId = "seed-1", payload = """{"value":"seed"}"""),
                    ),
                firstStep = "resume-step",
            )
            val claimed = store.claim("resume-step", run.id, 1, 50.milliseconds, "stuck-worker").single()
            claimed.status shouldBe WorkItemStatus.IN_PROGRESS

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
                                workerPollInterval = 10.milliseconds,
                                watchdogInterval = 10.milliseconds,
                                leaseDuration = 1.seconds,
                                workerClaimLimit = 8,
                            ),
                    )
                }

            try {
                waitUntil {
                    store.getWorkItemBySourceId(run.id, "seed-1")?.status == WorkItemStatus.COMPLETED
                }

                val item = store.getWorkItemBySourceId(run.id, "seed-1")!!
                item.status shouldBe WorkItemStatus.COMPLETED
                item.payloadJson.shouldBeNull()
                item.attemptCount shouldBe 1
            } finally {
                executable.stop()
                orchestrator.shutdown()
            }
        }
    })

private suspend fun waitUntil(
    timeout: Duration = 3.seconds,
    pollInterval: Duration = 10.milliseconds,
    condition: suspend () -> Boolean,
) {
    val started = TimeSource.Monotonic.markNow()
    while (!condition()) {
        if (started.elapsedNow() >= timeout) {
            error("Condition was not met within $timeout.")
        }
        delay(pollInterval)
    }
}

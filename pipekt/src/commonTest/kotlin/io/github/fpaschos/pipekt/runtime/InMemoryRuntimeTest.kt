package io.github.fpaschos.pipekt.runtime

import arrow.core.raise.context.raise
import io.github.fpaschos.pipekt.adapters.fake.FakeSourceAdapter
import io.github.fpaschos.pipekt.core.BarrierResult
import io.github.fpaschos.pipekt.core.Clock
import io.github.fpaschos.pipekt.core.FilteredReason
import io.github.fpaschos.pipekt.core.FinalizerStartResult
import io.github.fpaschos.pipekt.core.IngressRecord
import io.github.fpaschos.pipekt.core.RetryPolicy
import io.github.fpaschos.pipekt.core.RunStatus
import io.github.fpaschos.pipekt.core.pipeline
import io.github.fpaschos.pipekt.store.WorkItemStatus
import io.github.fpaschos.pipekt.store.fake.InMemoryStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class InMemoryRuntimeTest :
    FunSpec({

        fun fakeClock(startMs: Long = 1_000L) =
            object : Clock {
                var time = startMs

                override fun nowMs() = time
            }

        fun ingressOf(vararg payloads: String) =
            payloads.map { p ->
                IngressRecord<String>(sourceId = "src-$p", payload = p)
            }

        // ── Happy path ────────────────────────────────────────────────────────────

        test("happy path: records pass through a step and run is finalized") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("happy-path") {
                source("src", adapter)
                step<String, String>("upper") { it.uppercase() }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            val run = runtime.startRun(ingressOf("hello", "world"))

            run.pipelineName shouldBe "happy-path"

            val items = store.allWorkItems()
            items shouldHaveSize 2
            items.all { it.status == WorkItemStatus.COMPLETED } shouldBe true
            items.map { it.payloadJson }.toSet() shouldBe setOf("HELLO", "WORLD")
        }

        test("happy path: run status is FINALIZED after all items complete") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("finalize-test") {
                source("src", adapter)
                step<String, String>("noop") { it }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            val run = runtime.startRun(ingressOf("a"))

            val finalRun = store.getRun(run.id)!!
            finalRun.status shouldBe RunStatus.FINALIZED
        }

        // ── Filter ────────────────────────────────────────────────────────────────

        test("filter: items not passing predicate are marked FILTERED") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("filter-test") {
                source("src", adapter)
                filter<Nothing>("drop-short", filteredReason = FilteredReason.BELOW_THRESHOLD) {
                    it.length > 3
                }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            runtime.startRun(ingressOf("hi", "hello"))

            val items = store.allWorkItems()
            items shouldHaveSize 2
            val filtered = items.filter { it.status == WorkItemStatus.FILTERED }
            val completed = items.filter { it.status == WorkItemStatus.COMPLETED }
            filtered shouldHaveSize 1
            completed shouldHaveSize 1
            filtered.first().payloadJson shouldBe "hi"
        }

        // ── Barrier wait ──────────────────────────────────────────────────────────

        test("barrier: run transitions to AWAITING_BARRIER when predecessor items are pending") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("barrier-test") {
                source("src", adapter)
                step<String, String>("process") { it }
                barrier("wait-all", predecessorStep = "process")
                finalizer<String>("fin") { _ -> }
            }.getOrNull()!!

            val run = store.createRun("barrier-test", 1000L)

            val result = store.evaluateBarrier(run.id, "process")
            result shouldBe BarrierResult.WAITING
        }

        test("barrier: run proceeds after all predecessor items reach terminal state") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("barrier-proceed") {
                source("src", adapter)
                step<String, String>("process") { it.uppercase() }
                barrier("wait-all", predecessorStep = "process")
                finalizer<String>("fin") { _ -> }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            val run = runtime.startRun(ingressOf("x", "y"))

            val finalRun = store.getRun(run.id)!!
            finalRun.status shouldBe RunStatus.FINALIZED
        }

        // ── Finalizer exactly-once ────────────────────────────────────────────────

        test("finalizer: tryStartFinalizer returns STARTED only once per run") {
            val store = InMemoryStore()
            val run = store.createRun("fin-test", 1000L)

            val first = store.tryStartFinalizer(run.id, 1000L)
            val second = store.tryStartFinalizer(run.id, 2000L)

            first shouldBe FinalizerStartResult.STARTED
            second shouldBe FinalizerStartResult.ALREADY_STARTED
        }

        test("finalizer: exactly-once execution even with concurrent startRun calls") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            var finalizerCallCount = 0

            val pipelineDef = pipeline<String>("exactly-once") {
                source("src", adapter)
                step<String, String>("step1") { it }
                barrier("b1", predecessorStep = "step1")
                finalizer<String>("fin") { _ -> finalizerCallCount++ }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            val run = runtime.startRun(ingressOf("item1"))

            store.getRun(run.id)!!.status shouldBe RunStatus.FINALIZED
            finalizerCallCount shouldBe 1
        }

        // ── Retry ─────────────────────────────────────────────────────────────────

        test("step with maxAttempts=1 marks item as FAILED on error") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("retry-test") {
                source("src", adapter)
                step<String, String>("fail-step", retryPolicy = RetryPolicy(maxAttempts = 1)) {
                    raise("always fails")
                }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            runtime.startRun(ingressOf("item1"))

            val items = store.allWorkItems()
            items shouldHaveSize 1
            items.first().status shouldBe WorkItemStatus.FAILED
        }

        test("step with maxAttempts=3 records multiple attempt records before failing") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("multi-retry") {
                source("src", adapter)
                step<String, String>("retry-step", retryPolicy = RetryPolicy(maxAttempts = 3)) {
                    raise("fail")
                }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            runtime.startRun(ingressOf("item1"))

            val items = store.allWorkItems()
            items shouldHaveSize 1
            val attempts = store.getAttempts(items.first().id)
            attempts shouldHaveSize 3
            items.first().status shouldBe WorkItemStatus.FAILED
        }

        // ── Duplicate ingress ─────────────────────────────────────────────────────

        test("duplicate ingress records are deduplicated by sourceId") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("dedup-test") {
                source("src", adapter)
                step<String, String>("process") { it }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())
            val record = IngressRecord<String>(sourceId = "same-id", payload = "hello")
            runtime.startRun(listOf(record, record))

            val items = store.allWorkItems()
            items shouldHaveSize 1
        }

        // ── Resume ────────────────────────────────────────────────────────────────

        test("resumeRuns picks up active runs and completes them") {
            val store = InMemoryStore()
            val adapter = FakeSourceAdapter<String>()
            val pipelineDef = pipeline<String>("resume-test") {
                source("src", adapter)
                step<String, String>("noop") { it }
            }.getOrNull()!!

            val runtime = InMemoryRuntime(pipelineDef, store, fakeClock())

            val run = store.createRun("resume-test", 1000L)
            store.updateRunStatus(run.id, RunStatus.IN_PROGRESS, 1000L)
            store.appendIngress(
                runId = run.id,
                record = IngressRecord<String>(sourceId = "src-1", payload = "test"),
                payloadJson = "test",
                stepName = "noop",
                nowMs = 1000L,
            )

            runtime.resumeRuns()

            val items = store.allWorkItems()
            items shouldHaveSize 1
            items.first().status shouldBe WorkItemStatus.COMPLETED
        }
    })

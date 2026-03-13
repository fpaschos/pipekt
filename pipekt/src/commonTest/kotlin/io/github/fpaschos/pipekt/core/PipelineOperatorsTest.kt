package io.github.fpaschos.pipekt.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PipelineOperatorsTest :
    FunSpec({

        fun fakeAdapter() =
            object : SourceAdapter<String> {
                override suspend fun poll(maxItems: Int) = emptyList<SourceRecord<String>>()

                override suspend fun ack(records: List<SourceRecord<String>>) = Unit

                override suspend fun nack(
                    records: List<SourceRecord<String>>,
                    retry: Boolean,
                ) = Unit
            }

        test("StepDef retryPolicy is preserved") {
            val policy = RetryPolicy(maxAttempts = 3, backoffMs = 500L)
            val result =
                pipeline("retry-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String>("with-retry", retryPolicy = policy) { it }
                }
            result.isRight() shouldBe true
            val stepOp =
                result
                    .getOrNull()!!
                    .operators
                    .filterIsInstance<StepDef<*, *, *>>()
                    .first()
            stepOp.retryPolicy shouldBe policy
        }

        test("FilterDef filteredReason is preserved") {
            val result =
                pipeline("filter-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter<Nothing>("dedup", filteredReason = FilteredReason.DUPLICATE) { true }
                }
            result.isRight() shouldBe true
            val filterOp =
                result
                    .getOrNull()!!
                    .operators
                    .filterIsInstance<FilterDef<*, *>>()
                    .first()
            filterOp.filteredReason shouldBe FilteredReason.DUPLICATE
        }

        test("FilterDef filteredReason defaults to BELOW_THRESHOLD") {
            val result =
                pipeline("filter-default", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter<Nothing>("default-filter") { true }
                }
            result.isRight() shouldBe true
            val filterOp =
                result
                    .getOrNull()!!
                    .operators
                    .filterIsInstance<FilterDef<*, *>>()
                    .first()
            filterOp.filteredReason shouldBe FilteredReason.BELOW_THRESHOLD
        }

        test("PersistEachDef name is preserved") {
            val result =
                pipeline("persist-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                    persistEach("checkpoint-1")
                }
            result.isRight() shouldBe true
            val persistOp =
                result
                    .getOrNull()!!
                    .operators
                    .filterIsInstance<PersistEachDef>()
                    .first()
            persistOp.name shouldBe "checkpoint-1"
        }

        test("operator list order is retained") {
            val result =
                pipeline("order-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter<Nothing>("a") { true }
                    step<String, String>("b") { it }
                    persistEach("c")
                    step<String, String>("d") { it }
                }
            result.isRight() shouldBe true
            val ops = result.getOrNull()!!.operators
            ops.map { it.name } shouldBe listOf("a", "b", "c", "d")
        }

        test("source definition is preserved in pipeline") {
            val adapter = fakeAdapter()
            val result =
                pipeline("src-test", maxInFlight = 10) {
                    source("my-source", adapter)
                    step<String, String>("step1") { it }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            def.source.name shouldBe "my-source"
            def.source.adapter shouldBe adapter
        }

        test("pipeline with filter step and persistEach builds successfully") {
            val result =
                pipeline("full-pipeline", maxInFlight = 50) {
                    source("src", fakeAdapter())
                    filter<Nothing>("filter1") { it.isNotEmpty() }
                    step<String, String>("enrich") { it.uppercase() }
                    persistEach("checkpoint")
                    step<String, String>("publish") { it }
                }
            result.isRight() shouldBe true
            val ops = result.getOrNull()!!.operators
            ops.size shouldBe 4
        }
    })

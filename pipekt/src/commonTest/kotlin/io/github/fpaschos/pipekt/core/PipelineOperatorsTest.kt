package io.github.fpaschos.pipekt.core

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.typeOf

/**
 * Tests for operator definitions and their preservation in [PipelineDefinition].
 *
 * **Contract and behavior coverage:**
 * - [StepDef]: retryPolicy preserved (maxAttempts, backoffMs); inputType and outputType captured.
 * - [FilterDef]: filteredReason default (BELOW_THRESHOLD) and override (e.g. DUPLICATE); inputType captured.
 * - [PersistEachDef]: name preserved; no function.
 * - Operator order and source preservation in the built definition.
 * - Full pipeline with filter + persistEach builds and retains correct operator list.
 */
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
            val def =
                pipeline("retry-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String>("with-retry", retryPolicy = policy) { it }
                }.shouldBeRight()
            val stepOp = def.operators.filterIsInstance<StepDef<*, *>>().first()
            stepOp.name shouldBe "with-retry"
            stepOp.retryPolicy shouldBe policy
        }

        test("StepDef inputType and outputType are captured correctly") {
            val def =
                pipeline("type-capture-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, Int>("to-int") { it.length }
                }.shouldBeRight()
            val stepOp = def.operators.filterIsInstance<StepDef<*, *>>().first()
            stepOp.inputType shouldBe typeOf<String>()
            stepOp.outputType shouldBe typeOf<Int>()
        }

        test("FilterDef filteredReason is preserved") {
            val def =
                pipeline("filter-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter<String>("dedup", filteredReason = FilteredReason.DUPLICATE) { true }
                }.shouldBeRight()
            val filterOp = def.operators.filterIsInstance<FilterDef<*>>().first()
            filterOp.filteredReason shouldBe FilteredReason.DUPLICATE
        }

        test("FilterDef filteredReason defaults to BELOW_THRESHOLD") {
            val def =
                pipeline("filter-default", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter<String>("default-filter") { true }
                }.shouldBeRight()
            val filterOp = def.operators.filterIsInstance<FilterDef<*>>().first()
            filterOp.filteredReason shouldBe FilteredReason.BELOW_THRESHOLD
        }

        test("FilterDef inputType is captured correctly") {
            val def =
                pipeline("filter-type-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter<String>("f") { it.isNotEmpty() }
                }.shouldBeRight()
            val filterOp = def.operators.filterIsInstance<FilterDef<*>>().first()
            filterOp.inputType shouldBe typeOf<String>()
        }

        test("PersistEachDef name is preserved") {
            val def =
                pipeline("persist-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                    persistEach("checkpoint-1")
                }.shouldBeRight()
            val persistOp = def.operators.filterIsInstance<PersistEachDef>().first()
            persistOp.name shouldBe "checkpoint-1"
        }

        test("operator list order is retained") {
            val def =
                pipeline("order-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter<String>("a") { true }
                    step<String, String>("b") { it }
                    persistEach("c")
                    step<String, String>("d") { it }
                }.shouldBeRight()
            def.operators.map { it.name } shouldBe listOf("a", "b", "c", "d")
        }

        test("source definition is preserved in pipeline") {
            val adapter = fakeAdapter()
            val def =
                pipeline("src-test", maxInFlight = 10) {
                    source("my-source", adapter)
                    step<String, String>("step1") { it }
                }.shouldBeRight()
            def.source.name shouldBe "my-source"
            def.source.adapter shouldBe adapter
        }

        test("pipeline with filter step and persistEach builds successfully") {
            val def =
                pipeline("full-pipeline", maxInFlight = 50) {
                    source("src", fakeAdapter())
                    filter<String>("filter1") { it.isNotEmpty() }
                    step<String, String>("enrich") { it.uppercase() }
                    persistEach("checkpoint")
                    step<String, String>("publish") { it }
                }.shouldBeRight()
            def.operators.size shouldBe 4
        }
    })

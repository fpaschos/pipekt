package io.github.fpaschos.pipekt.core

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.typeOf

/**
 * Tests for operator definitions and their preservation in [PipelineDefinition].
 *
 * Operator types [StepDef] and [FilterDef] are built via the [PipelineBuilder] DSL. Each operator
 * call is an independent statement on the block receiver — no chaining is required and no
 * explicit input/output type annotations are needed at call sites.
 *
 * **Contract and behavior coverage:**
 * - [StepDef]: retryPolicy preserved (maxAttempts, backoffMs); inputType captured from the
 *   preceding operator's output type; outputType captured via reified inference from the lambda.
 * - [FilterDef]: filteredReason default (BELOW_THRESHOLD) and override (e.g. DUPLICATE); inputType
 *   captured from the current flowing output type.
 * - Operator order and source preservation in the built definition.
 * - Full pipeline with filter + step builds and retains correct operator list.
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
                    step("with-retry", retryPolicy = policy) { s: String -> s }
                }.shouldBeRight()
            val stepOp = def.operators.filterIsInstance<StepDef<*, *>>().first()
            stepOp.name shouldBe "with-retry"
            stepOp.retryPolicy shouldBe policy
        }

        test("StepDef inputType and outputType are captured correctly") {
            val def =
                pipeline("type-capture-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step("to-int") { it: String -> it.length }
                }.shouldBeRight()
            val stepOp = def.operators.filterIsInstance<StepDef<*, *>>().first()
            stepOp.inputType shouldBe typeOf<String>()
            stepOp.outputType shouldBe typeOf<Int>()
        }

        test("FilterDef filteredReason is preserved") {
            val def =
                pipeline("filter-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter("dedup", filteredReason = FilteredReason.DUPLICATE) { _: String -> true }
                }.shouldBeRight()
            val filterOp = def.operators.filterIsInstance<FilterDef<*>>().first()
            filterOp.filteredReason shouldBe FilteredReason.DUPLICATE
        }

        test("FilterDef filteredReason defaults to BELOW_THRESHOLD") {
            val def =
                pipeline("filter-default", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter("default-filter") { _: String -> true }
                }.shouldBeRight()
            val filterOp = def.operators.filterIsInstance<FilterDef<*>>().first()
            filterOp.filteredReason shouldBe FilteredReason.BELOW_THRESHOLD
        }

        test("FilterDef inputType is captured correctly") {
            val def =
                pipeline("filter-type-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter("f") { it: String -> it.isNotEmpty() }
                }.shouldBeRight()
            val filterOp = def.operators.filterIsInstance<FilterDef<*>>().first()
            filterOp.inputType shouldBe typeOf<String>()
        }

        test("operator list order is retained") {
            val def =
                pipeline("order-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    filter("a") { _: String -> true }
                    step("b") { it: String -> it }
                    step("c") { it: String -> it }
                    step("d") { it: String -> it }
                }.shouldBeRight()
            def.operators.map { it.name } shouldBe listOf("a", "b", "c", "d")
        }

        test("source definition is preserved in pipeline") {
            val adapter = fakeAdapter()
            val def =
                pipeline("src-test", maxInFlight = 10) {
                    source("my-source", adapter)
                    step("step1") { it: String -> it }
                }.shouldBeRight()
            def.source.name shouldBe "my-source"
            def.source.adapter shouldBe adapter
        }

        test("pipeline with filter and steps builds successfully") {
            val def =
                pipeline("full-pipeline", maxInFlight = 50) {
                    source("src", fakeAdapter())
                    filter("filter1") { it: String -> it.isNotEmpty() }
                    step("enrich") { it: String -> it.uppercase() }
                    step("publish") { it: String -> it }
                }.shouldBeRight()
            def.operators.size shouldBe 3
        }
    })

package io.github.fpaschos.pipekt.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TypeChainValidationTest :
    FunSpec({

        fun fakeAdapter() =
            object : SourceAdapter<String> {
                override suspend fun poll(max: Int) = emptyList<SourceRecord<String>>()

                override suspend fun ack(id: String) = Unit

                override suspend fun nack(id: String) = Unit
            }

        test("pipeline with chained steps builds correctly") {
            val result =
                pipeline<String>("chain-test") {
                    source("src", fakeAdapter())
                    step<Int, Nothing>("parse") { it.length }
                    step<String, Nothing>("format") { "len=$it" }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            def.operators.size shouldBe 2
            def.operators[0].name shouldBe "parse"
            def.operators[1].name shouldBe "format"
        }

        test("pipeline retains all operator names in order") {
            val result =
                pipeline<String>("order-test") {
                    source("src", fakeAdapter())
                    step<String, String>("a") { it }
                    filter<Nothing>("b") { it.isNotEmpty() }
                    persistEach("c")
                    step<String, String>("d") { it }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            def.operators.map { it.name } shouldBe listOf("a", "b", "c", "d")
        }

        test("pipeline source is preserved in definition") {
            val adapter = fakeAdapter()
            val result =
                pipeline<String>("src-test") {
                    source("my-source", adapter)
                    step<String, String>("step") { it }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            def.source.name shouldBe "my-source"
            def.source.adapter shouldBe adapter
        }

        test("filter with custom reason is preserved") {
            val result =
                pipeline<String>("filter-test") {
                    source("src", fakeAdapter())
                    filter<Nothing>("dedup", filteredReason = FilteredReason.DUPLICATE) { true }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            val filterOp = def.operators.filterIsInstance<FilterDef<*, *>>().first()
            filterOp.filteredReason shouldBe FilteredReason.DUPLICATE
        }

        test("retry policy is preserved on step definition") {
            val policy = RetryPolicy(maxAttempts = 3, backoffMs = 500L)
            val result =
                pipeline<String>("retry-test") {
                    source("src", fakeAdapter())
                    step<String, String>("with-retry", retryPolicy = policy) { it }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            val stepOp = def.operators.filterIsInstance<StepDef<*, *, *>>().first()
            stepOp.retryPolicy shouldBe policy
        }
    })

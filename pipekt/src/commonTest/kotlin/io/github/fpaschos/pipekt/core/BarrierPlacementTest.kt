package io.github.fpaschos.pipekt.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BarrierPlacementTest :
    FunSpec({

        fun fakeAdapter() =
            object : SourceAdapter<String> {
                override suspend fun poll(max: Int) = emptyList<SourceRecord<String>>()

                override suspend fun ack(id: String) = Unit

                override suspend fun nack(id: String) = Unit
            }

        test("barrier referencing a valid predecessor step builds successfully") {
            val result =
                pipeline<String>("barrier-valid") {
                    source("src", fakeAdapter())
                    step<String, String>("process") { it }
                    barrier("wait-all", predecessorStep = "process")
                    finalizer<String>("fin") { _ -> }
                }
            result.isRight() shouldBe true
        }

        test("barrier with no finite predecessor is rejected") {
            val result =
                pipeline<String>("barrier-invalid") {
                    source("src", fakeAdapter())
                    step<String, String>("process") { it }
                    barrier("wait-all", predecessorStep = "nonexistent-step")
                }
            result.isLeft() shouldBe true
            val errors = result.leftOrNull()!!
            errors.any { it is PipelineValidationError.BarrierWithNoFinitePredecessor } shouldBe true
            val err = errors.filterIsInstance<PipelineValidationError.BarrierWithNoFinitePredecessor>().first()
            err.barrierName shouldBe "wait-all"
            err.predecessorStep shouldBe "nonexistent-step"
        }

        test("barrier cannot reference the source as a predecessor") {
            val result =
                pipeline<String>("barrier-source-pred") {
                    source("src", fakeAdapter())
                    barrier("wait-all", predecessorStep = "src")
                }
            result.isLeft() shouldBe true
            val errors = result.leftOrNull()!!
            errors.any { it is PipelineValidationError.BarrierWithNoFinitePredecessor } shouldBe true
        }

        test("multiple barriers with valid predecessors all build successfully") {
            val result =
                pipeline<String>("multi-barrier") {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                    barrier("b1", predecessorStep = "step1")
                    step<String, String>("step2") { it }
                    barrier("b2", predecessorStep = "step2")
                    finalizer<String>("fin") { _ -> }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            val barriers = def.operators.filterIsInstance<BarrierDef>()
            barriers.size shouldBe 2
        }

        test("barrier referencing another barrier step is valid") {
            val result =
                pipeline<String>("barrier-chain") {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                    barrier("b1", predecessorStep = "step1")
                    barrier("b2", predecessorStep = "b1")
                }
            result.isRight() shouldBe true
        }
    })

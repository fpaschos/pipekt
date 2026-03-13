package io.github.fpaschos.pipekt.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PipelineValidationTest :
    FunSpec({

        fun fakeAdapter() =
            object : SourceAdapter<String> {
                override suspend fun poll(max: Int) = emptyList<SourceRecord<String>>()

                override suspend fun ack(id: String) = Unit

                override suspend fun nack(id: String) = Unit
            }

        test("valid pipeline builds successfully") {
            val result =
                pipeline<String>("test-pipeline") {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                }
            result.isRight() shouldBe true
            result.getOrNull()!!.name shouldBe "test-pipeline"
        }

        test("duplicate step names are rejected") {
            val result =
                pipeline<String>("test-pipeline") {
                    source("src", fakeAdapter())
                    step<String, String>("duplicate") { it }
                    step<String, String>("duplicate") { it }
                }
            result.isLeft() shouldBe true
            val errors = result.leftOrNull()!!
            errors.any { it is PipelineValidationError.DuplicateStepName } shouldBe true
            val dupeError = errors.filterIsInstance<PipelineValidationError.DuplicateStepName>().first()
            dupeError.name shouldBe "duplicate"
        }

        test("duplicate source name clashing with step name is rejected") {
            val result =
                pipeline<String>("test-pipeline") {
                    source("shared-name", fakeAdapter())
                    step<String, String>("shared-name") { it }
                }
            result.isLeft() shouldBe true
            val errors = result.leftOrNull()!!
            errors.any { it is PipelineValidationError.DuplicateStepName } shouldBe true
        }

        test("multiple finalizers are rejected") {
            val result =
                pipeline<String>("test-pipeline") {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                    finalizer<String>("fin1") { _ -> }
                    finalizer<String>("fin2") { _ -> }
                }
            result.isLeft() shouldBe true
            val errors = result.leftOrNull()!!
            val multiFinError = errors.filterIsInstance<PipelineValidationError.MultipleFinalizersFound>().firstOrNull()
            multiFinError shouldBe PipelineValidationError.MultipleFinalizersFound(listOf("fin1", "fin2"))
        }

        test("missing source returns NoSourceDefined error") {
            val result =
                pipeline<String>("no-source") {
                    step<String, String>("step1") { it }
                }
            result.isLeft() shouldBe true
            result.leftOrNull()!!.any { it is PipelineValidationError.NoSourceDefined } shouldBe true
        }

        test("empty operators list returns EmptyPipeline error") {
            val sourceDef = SourceDef("src", fakeAdapter())
            val result = validate(name = "empty", source = sourceDef, operators = emptyList())
            result.isLeft() shouldBe true
            result.leftOrNull()!!.any { it is PipelineValidationError.EmptyPipeline } shouldBe true
        }

        test("valid pipeline with filter, persistEach and finalizer builds") {
            val result =
                pipeline<String>("full-pipeline") {
                    source("src", fakeAdapter())
                    filter<Nothing>("filter1") { it.isNotEmpty() }
                    persistEach("persist1")
                    step<Int, Nothing>("step1") { it.length }
                    barrier("barrier1", predecessorStep = "step1")
                    finalizer<Int>("fin1") { _ -> }
                }
            result.isRight() shouldBe true
        }
    })

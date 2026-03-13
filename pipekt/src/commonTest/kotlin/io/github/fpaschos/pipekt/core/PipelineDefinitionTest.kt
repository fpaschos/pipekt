package io.github.fpaschos.pipekt.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PipelineDefinitionTest :
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

        test("valid pipeline builds successfully") {
            val result =
                pipeline<String>("test-pipeline", maxInFlight = 100) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                }
            result.isRight() shouldBe true
            val def = result.getOrNull()!!
            def.name shouldBe "test-pipeline"
            def.maxInFlight shouldBe 100
        }

        test("retentionDays defaults to 30") {
            val result =
                pipeline<String>("default-retention", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                }
            result.getOrNull()!!.retentionDays shouldBe 30
        }

        test("retentionDays can be overridden") {
            val result =
                pipeline<String>("custom-retention", maxInFlight = 10, retentionDays = 7) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                }
            result.getOrNull()!!.retentionDays shouldBe 7
        }

        test("duplicate step names are rejected") {
            val result =
                pipeline<String>("dupe-test", maxInFlight = 10) {
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

        test("source name clashing with step name is rejected") {
            val result =
                pipeline<String>("clash-test", maxInFlight = 10) {
                    source("shared-name", fakeAdapter())
                    step<String, String>("shared-name") { it }
                }
            result.isLeft() shouldBe true
            result.leftOrNull()!!.any { it is PipelineValidationError.DuplicateStepName } shouldBe true
        }

        test("missing source returns NoSourceDefined") {
            val result =
                validate(
                    name = "no-source",
                    source = null,
                    operators = listOf(PersistEachDef("step1")),
                    maxInFlight = 10,
                )
            result.isLeft() shouldBe true
            result.leftOrNull()!!.any { it is PipelineValidationError.NoSourceDefined } shouldBe true
        }

        test("empty operators list returns EmptyPipeline") {
            val sourceDef = SourceDef("src", fakeAdapter())
            val result =
                validate(
                    name = "empty",
                    source = sourceDef,
                    operators = emptyList(),
                    maxInFlight = 10,
                )
            result.isLeft() shouldBe true
            result.leftOrNull()!!.any { it is PipelineValidationError.EmptyPipeline } shouldBe true
        }

        test("maxInFlight of zero returns InvalidMaxInFlight") {
            val result =
                validate(
                    name = "bad-flight",
                    source = SourceDef("src", fakeAdapter()),
                    operators = listOf(PersistEachDef("step1")),
                    maxInFlight = 0,
                )
            result.isLeft() shouldBe true
            result.leftOrNull()!!.any { it is PipelineValidationError.InvalidMaxInFlight } shouldBe true
        }

        test("negative maxInFlight returns InvalidMaxInFlight") {
            val result =
                validate(
                    name = "bad-flight",
                    source = SourceDef("src", fakeAdapter()),
                    operators = listOf(PersistEachDef("step1")),
                    maxInFlight = -5,
                )
            result.isLeft() shouldBe true
            result.leftOrNull()!!.any { it is PipelineValidationError.InvalidMaxInFlight } shouldBe true
        }

        test("multiple validation errors are all reported together") {
            val result =
                validate(
                    name = "multi-error",
                    source = null,
                    operators = emptyList(),
                    maxInFlight = 0,
                )
            result.isLeft() shouldBe true
            val errors = result.leftOrNull()!!
            errors.any { it is PipelineValidationError.NoSourceDefined } shouldBe true
            errors.any { it is PipelineValidationError.EmptyPipeline } shouldBe true
            errors.any { it is PipelineValidationError.InvalidMaxInFlight } shouldBe true
        }
    })

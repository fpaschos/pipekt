package io.github.fpaschos.pipekt.core

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
            val def =
                pipeline("test-pipeline", maxInFlight = 100) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                }.shouldBeRight()
            def.name shouldBe "test-pipeline"
            def.maxInFlight shouldBe 100
        }

        test("retentionDays defaults to 30") {
            val def =
                pipeline("default-retention", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                }.shouldBeRight()
            def.retentionDays shouldBe 30
        }

        test("retentionDays can be overridden") {
            val def =
                pipeline("custom-retention", maxInFlight = 10, retentionDays = 7) {
                    source("src", fakeAdapter())
                    step<String, String>("step1") { it }
                }.shouldBeRight()
            def.retentionDays shouldBe 7
        }

        test("duplicate step names are rejected") {
            val errors =
                pipeline("dupe-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String>("duplicate") { it }
                    step<String, String>("duplicate") { it }
                }.shouldBeLeft()
            val dupeError = errors.filterIsInstance<PipelineValidationError.DuplicateStepName>().first()
            dupeError.name shouldBe "duplicate"
        }

        test("source name clashing with step name is rejected") {
            val errors =
                pipeline("clash-test", maxInFlight = 10) {
                    source("shared-name", fakeAdapter())
                    step<String, String>("shared-name") { it }
                }.shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.DuplicateStepName>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.DuplicateStepName("shared-name"),
            )
        }

        test("missing source returns NoSourceDefined") {
            val errors =
                validate(
                    name = "no-source",
                    source = null,
                    operators = listOf(PersistEachDef("step1")),
                    maxInFlight = 10,
                ).shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.NoSourceDefined>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.NoSourceDefined,
            )
        }

        test("empty operators list returns EmptyPipeline") {
            val sourceDef = SourceDef("src", fakeAdapter())
            val errors =
                validate(
                    name = "empty",
                    source = sourceDef,
                    operators = emptyList(),
                    maxInFlight = 10,
                ).shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.EmptyPipeline>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.EmptyPipeline,
            )
        }

        test("maxInFlight of zero returns InvalidMaxInFlight") {
            val errors =
                validate(
                    name = "bad-flight",
                    source = SourceDef("src", fakeAdapter()),
                    operators = listOf(PersistEachDef("step1")),
                    maxInFlight = 0,
                ).shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.InvalidMaxInFlight>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.InvalidMaxInFlight,
            )
        }

        test("negative maxInFlight returns InvalidMaxInFlight") {
            val errors =
                validate(
                    name = "bad-flight",
                    source = SourceDef("src", fakeAdapter()),
                    operators = listOf(PersistEachDef("step1")),
                    maxInFlight = -5,
                ).shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.InvalidMaxInFlight>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.InvalidMaxInFlight,
            )
        }

        test("multiple validation errors are all reported together") {
            val errors =
                validate(
                    name = "multi-error",
                    source = null,
                    operators = emptyList(),
                    maxInFlight = 0,
                ).shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.NoSourceDefined>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.NoSourceDefined,
            )
            errors.filterIsInstance<PipelineValidationError.EmptyPipeline>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.EmptyPipeline,
            )
            errors.filterIsInstance<PipelineValidationError.InvalidMaxInFlight>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.InvalidMaxInFlight,
            )
        }
    })

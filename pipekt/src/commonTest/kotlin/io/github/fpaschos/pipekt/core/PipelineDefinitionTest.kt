package io.github.fpaschos.pipekt.core

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.reflect.typeOf

/**
 * Tests for [PipelineDefinition] validation and the [pipeline] DSL builder.
 *
 * **Contract and behavior coverage:**
 * - Valid pipeline builds (name, maxInFlight, source + step).
 * - [PipelineDefinition.retentionDays]: default 30 and override.
 * - Validation errors: duplicate step names (including source/step name clash), no source defined,
 *   empty operators, invalid or negative maxInFlight; multiple errors reported together.
 * - DSL builder: source, step, filter, persistEach; build() returns Either consistent with [validate].
 * - Type-chain validation: [PipelineValidationError.TypeMismatch] for step/filter input type mismatches;
 *   [PersistEachDef] is transparent; valid same-type chains pass.
 */
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
                    step<String, String, ItemFailure>("step1") { it }
                }.shouldBeRight()
            def.name shouldBe "test-pipeline"
            def.maxInFlight shouldBe 100
        }

        test("retentionDays defaults to 30") {
            val def =
                pipeline("default-retention", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String, ItemFailure>("step1") { it }
                }.shouldBeRight()
            def.retentionDays shouldBe 30
        }

        test("retentionDays can be overridden") {
            val def =
                pipeline("custom-retention", maxInFlight = 10, retentionDays = 7) {
                    source("src", fakeAdapter())
                    step<String, String, ItemFailure>("step1") { it }
                }.shouldBeRight()
            def.retentionDays shouldBe 7
        }

        test("duplicate step names are rejected") {
            val errors =
                pipeline("dupe-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, String, ItemFailure>("duplicate") { it }
                    step<String, String, ItemFailure>("duplicate") { it }
                }.shouldBeLeft()
            val dupeError = errors.filterIsInstance<PipelineValidationError.DuplicateStepName>().first()
            dupeError.name shouldBe "duplicate"
        }

        test("source name clashing with step name is rejected") {
            val errors =
                pipeline("clash-test", maxInFlight = 10) {
                    source("shared-name", fakeAdapter())
                    step<String, String, ItemFailure>("shared-name") { it }
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
                    sourceType = typeOf<String>(),
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
                    sourceType = typeOf<String>(),
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
                    sourceType = typeOf<String>(),
                    operators = listOf(PersistEachDef("step1")),
                    maxInFlight = 0,
                ).shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.InvalidMaxInFlight>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.InvalidMaxInFlight,
            )
        }

        test("maxInFlight of 1 is accepted") {
            pipeline("min-flight", maxInFlight = 1) {
                source("src", fakeAdapter())
                step<String, String, ItemFailure>("step1") { it }
            }.shouldBeRight()
        }

        test("negative maxInFlight returns InvalidMaxInFlight") {
            val errors =
                validate(
                    name = "bad-flight",
                    source = SourceDef("src", fakeAdapter()),
                    sourceType = typeOf<String>(),
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
                    sourceType = typeOf<String>(),
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

        // ── Type-chain validation ──────────────────────────────────────────────

        test("valid String-to-String type chain passes validation") {
            pipeline("chain-valid", maxInFlight = 10) {
                source("src", fakeAdapter())
                step<String, String, ItemFailure>("step1") { it }
                step<String, String, ItemFailure>("step2") { it.uppercase() }
            }.shouldBeRight()
        }

        test("type mismatch between adjacent steps is rejected") {
            val errors =
                pipeline("chain-mismatch", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, Int, ItemFailure>("to-int") { it.length }
                    step<String, String, ItemFailure>("expects-string") { it }
                }.shouldBeLeft()
            val mismatch = errors.filterIsInstance<PipelineValidationError.TypeMismatch>().first()
            mismatch.stepName shouldBe "expects-string"
            mismatch.expected shouldBe typeOf<String>()
            mismatch.actual shouldBe typeOf<Int>()
        }

        test("PersistEachDef is transparent in the type chain") {
            pipeline("chain-persist", maxInFlight = 10) {
                source("src", fakeAdapter())
                step<String, String, ItemFailure>("step1") { it }
                persistEach("checkpoint")
                step<String, String, ItemFailure>("step2") { it }
            }.shouldBeRight()
        }

        test("filter with matching input type passes type-chain validation") {
            pipeline("chain-filter-valid", maxInFlight = 10) {
                source("src", fakeAdapter())
                filter<ItemFailure>("keep-nonempty") { it.isNotEmpty() }
                step<String, String, ItemFailure>("step1") { it }
            }.shouldBeRight()
        }

        test("multiple independent type mismatches are all reported") {
            // to-int: String -> Int (ok, matches source String)
            // wrong-int-1: Int -> String (ok, matches Int output of to-int)
            // wrong-string: Int -> Int mismatches: declared input Int but currentOutput is String
            // Use two separate mismatch points: String->Int->String->Int chain where
            // steps 2 and 4 expect Int but receive String output from prior steps.
            val errors =
                pipeline("chain-multi-mismatch", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step<String, Int, ItemFailure>("to-int") { it.length }
                    step<String, String, ItemFailure>("mismatch-1") { it }
                    step<Int, String, ItemFailure>("mismatch-2") { it.toString() }
                }.shouldBeLeft()
            val mismatches = errors.filterIsInstance<PipelineValidationError.TypeMismatch>()
            mismatches.map { it.stepName }.shouldContainExactlyInAnyOrder("mismatch-1", "mismatch-2")
        }
    })

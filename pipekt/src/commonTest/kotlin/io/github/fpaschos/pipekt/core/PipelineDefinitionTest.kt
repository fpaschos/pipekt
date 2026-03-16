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
 * - Valid pipeline builds (name, maxInFlight, source + step); operator types fully inferred, no
 *   explicit annotations required at step/filter call sites.
 * - [PipelineDefinition.retentionDays]: default 30 and override.
 * - Validation errors: duplicate step names (including source/step name clash), no source defined,
 *   empty operators, invalid or negative maxInFlight; multiple errors reported together.
 * - DSL builder: [PipelineBuilder] as block receiver; source, step, filter are independent
 *   statements — no chaining.
 * - Type-chain validation via [validate]: [PipelineValidationError.TypeMismatch] for step/filter
 *   input type mismatches; valid same-type chains pass.
 *   Type mismatches tested via [validate] directly (runtime-only enforcement).
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
                    step("step1") { it: String -> it }
                }.shouldBeRight()
            def.name shouldBe "test-pipeline"
            def.maxInFlight shouldBe 100
        }

        test("retentionDays defaults to 30") {
            val def =
                pipeline("default-retention", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step("step1") { it: String -> it }
                }.shouldBeRight()
            def.retentionDays shouldBe 30
        }

        test("retentionDays can be overridden") {
            val def =
                pipeline("custom-retention", maxInFlight = 10, retentionDays = 7) {
                    source("src", fakeAdapter())
                    step("step1") { it: String -> it }
                }.shouldBeRight()
            def.retentionDays shouldBe 7
        }

        test("duplicate step names are rejected") {
            val errors =
                pipeline("dupe-test", maxInFlight = 10) {
                    source("src", fakeAdapter())
                    step("duplicate") { it: String -> it }
                    step("duplicate") { it: String -> it }
                }.shouldBeLeft()
            val dupeError = errors.filterIsInstance<PipelineValidationError.DuplicateStepName>().first()
            dupeError.name shouldBe "duplicate"
        }

        test("source name clashing with step name is rejected") {
            val errors =
                pipeline("clash-test", maxInFlight = 10) {
                    source("shared-name", fakeAdapter())
                    step("shared-name") { it: String -> it }
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
                    operators =
                        listOf(
                            StepDef(
                                name = "step1",
                                fn = { _: String -> "" },
                                inputType = typeOf<String>(),
                                outputType = typeOf<String>(),
                            ),
                        ),
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
                    operators =
                        listOf(
                            StepDef(
                                name = "step1",
                                fn = { _: String -> "" },
                                inputType = typeOf<String>(),
                                outputType = typeOf<String>(),
                            ),
                        ),
                    maxInFlight = 0,
                ).shouldBeLeft()
            errors.filterIsInstance<PipelineValidationError.InvalidMaxInFlight>().shouldContainExactlyInAnyOrder(
                PipelineValidationError.InvalidMaxInFlight,
            )
        }

        test("maxInFlight of 1 is accepted") {
            pipeline("min-flight", maxInFlight = 1) {
                source("src", fakeAdapter())
                step("step1") { it: String -> it }
            }.shouldBeRight()
        }

        test("negative maxInFlight returns InvalidMaxInFlight") {
            val errors =
                validate(
                    name = "bad-flight",
                    source = SourceDef("src", fakeAdapter()),
                    sourceType = typeOf<String>(),
                    operators =
                        listOf(
                            StepDef(
                                name = "step1",
                                fn = { _: String -> "" },
                                inputType = typeOf<String>(),
                                outputType = typeOf<String>(),
                            ),
                        ),
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
        // Mismatched chains cannot be expressed via the DSL (compile-time enforcement);
        // these tests go through validate() directly with pre-built operator lists.

        test("valid String-to-String type chain passes validation") {
            pipeline("chain-valid", maxInFlight = 10) {
                source("src", fakeAdapter())
                step("step1") { it: String -> it }
                step("step2") { it: String -> it.uppercase() }
            }.shouldBeRight()
        }

        test("type mismatch between adjacent steps is rejected") {
            val toInt: StepFn<String, Int> = { s -> s.length }
            val expectsString: StepFn<String, String> = { s -> s }
            val errors =
                validate(
                    name = "chain-mismatch",
                    source = SourceDef("src", fakeAdapter()),
                    sourceType = typeOf<String>(),
                    operators =
                        listOf(
                            StepDef(
                                name = "to-int",
                                fn = toInt,
                                inputType = typeOf<String>(),
                                outputType = typeOf<Int>(),
                            ),
                            StepDef(
                                name = "expects-string",
                                fn = expectsString,
                                inputType = typeOf<String>(),
                                outputType = typeOf<String>(),
                            ),
                        ),
                    maxInFlight = 10,
                ).shouldBeLeft()
            val mismatch = errors.filterIsInstance<PipelineValidationError.TypeMismatch>().first()
            mismatch.stepName shouldBe "expects-string"
            mismatch.expected shouldBe typeOf<String>()
            mismatch.actual shouldBe typeOf<Int>()
        }

        test("filter with matching input type passes type-chain validation") {
            pipeline("chain-filter-valid", maxInFlight = 10) {
                source("src", fakeAdapter())
                filter("keep-nonempty") { it: String -> it.isNotEmpty() }
                step("step1") { it: String -> it }
            }.shouldBeRight()
        }

        test("multiple independent type mismatches are all reported") {
            val toInt: StepFn<String, Int> = { s -> s.length }
            val mismatch1: StepFn<String, String> = { s -> s }
            val mismatch2: StepFn<Int, String> = { i -> i.toString() }
            val errors =
                validate(
                    name = "chain-multi-mismatch",
                    source = SourceDef("src", fakeAdapter()),
                    sourceType = typeOf<String>(),
                    operators =
                        listOf(
                            StepDef(
                                name = "to-int",
                                fn = toInt,
                                inputType = typeOf<String>(),
                                outputType = typeOf<Int>(),
                            ),
                            StepDef(
                                name = "mismatch-1",
                                fn = mismatch1,
                                inputType = typeOf<String>(),
                                outputType = typeOf<String>(),
                            ),
                            StepDef(
                                name = "mismatch-2",
                                fn = mismatch2,
                                inputType = typeOf<Int>(),
                                outputType = typeOf<String>(),
                            ),
                        ),
                    maxInFlight = 10,
                ).shouldBeLeft()
            val mismatches = errors.filterIsInstance<PipelineValidationError.TypeMismatch>()
            mismatches.map { it.stepName }.shouldContainExactlyInAnyOrder("mismatch-1", "mismatch-2")
        }
    })

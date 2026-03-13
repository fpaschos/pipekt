package io.github.fpaschos.pipekt.core

import arrow.core.Either
import arrow.core.raise.context.raise
import arrow.core.raise.either
import arrow.core.raise.withError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for step function error ergonomics using the Arrow [Raise] DSL with [CoreFailure] and [ItemFailure].
 *
 * **Contract and behavior coverage:**
 * - [CoreFailure.Fatal]: raised step is captured as Left; [ItemFailure.isRetryable] is false,
 *   [ItemFailure.isFiltered] is false.
 * - [CoreFailure.Retryable]: [ItemFailure.isRetryable] returns true.
 * - [CoreFailure.Filtered]: [ItemFailure.isFiltered] returns true; message is the reason name.
 * - [CoreFailure.InfrastructureFailure]: treated as fatal by default (neither retryable nor filtered).
 * - User domain error implementing [ItemFailure] with custom routing works via the interface.
 * - Step that does not raise returns Right(output).
 * - [withError] can map domain errors to [CoreFailure] for uniform runtime handling.
 *
 * Step functions are defined outside [either] blocks to avoid context-parameter shadowing with the
 * compiler's `-XXLanguage:+ContextParameters` feature.
 */
class StepRaiseDslTest :
    FunSpec({

        // Minimal StepCtx for invoking step functions directly in tests.
        val ctx =
            StepCtx(
                pipelineName = "test",
                runId = "run-1",
                itemId = "item-1",
                itemKey = "key-1",
                stepName = "step-1",
                attempt = 1,
                startedAt = 0L,
            )

        test("step that does not raise returns Right(output)") {
            val fn: StepFn<String, String, CoreFailure> = { input -> input.uppercase() }
            val result: Either<CoreFailure, String> = either { with(ctx) { fn("hello") } }
            result.shouldBeRight() shouldBe "HELLO"
        }

        test("step raising CoreFailure.Fatal is captured as Left") {
            val fn: StepFn<String, String, CoreFailure> = { _ -> raise(CoreFailure.Fatal("bad input")) }
            val result: Either<CoreFailure, String> = either { with(ctx) { fn("x") } }
            val failure = result.shouldBeLeft()
            failure.shouldBeInstanceOf<CoreFailure.Fatal>()
            failure.message shouldBe "bad input"
            failure.isRetryable() shouldBe false
            failure.isFiltered() shouldBe false
        }

        test("CoreFailure.Retryable has isRetryable true") {
            val fn: StepFn<String, String, CoreFailure> = { _ -> raise(CoreFailure.Retryable("timeout", attempt = 1)) }
            val result: Either<CoreFailure, String> = either { with(ctx) { fn("x") } }
            val failure = result.shouldBeLeft()
            failure.shouldBeInstanceOf<CoreFailure.Retryable>()
            failure.isRetryable() shouldBe true
            failure.isFiltered() shouldBe false
        }

        test("CoreFailure.Filtered has isFiltered true and message equals reason name") {
            val fn: StepFn<String, String, CoreFailure> = { _ -> raise(CoreFailure.Filtered(FilteredReason.DUPLICATE)) }
            val result: Either<CoreFailure, String> = either { with(ctx) { fn("x") } }
            val failure = result.shouldBeLeft()
            failure.shouldBeInstanceOf<CoreFailure.Filtered>()
            failure.isFiltered() shouldBe true
            failure.isRetryable() shouldBe false
            failure.message shouldBe "DUPLICATE"
        }

        test("CoreFailure.InfrastructureFailure is fatal by default") {
            val failure = CoreFailure.InfrastructureFailure("db timeout")
            failure.isRetryable() shouldBe false
            failure.isFiltered() shouldBe false
            failure.message shouldBe "db timeout"
        }

        test("user domain error implementing ItemFailure with isRetryable routes correctly") {
            data class GatewayTimeout(
                val code: Int,
            ) : ItemFailure {
                override val message = "gateway timeout: $code"

                override fun isRetryable() = true
            }

            val fn: StepFn<String, String, GatewayTimeout> = { _ -> raise(GatewayTimeout(503)) }
            val result: Either<GatewayTimeout, String> = either { with(ctx) { fn("x") } }
            val failure = result.shouldBeLeft()
            failure.isRetryable() shouldBe true
            failure.isFiltered() shouldBe false
            failure.code shouldBe 503
        }

        test("withError maps domain error to CoreFailure for uniform runtime handling") {
            data class PaymentDeclined(
                val reason: String,
            ) : ItemFailure {
                override val message = "declined: $reason"
            }

            val fn: StepFn<String, String, PaymentDeclined> = { _ -> raise(PaymentDeclined("insufficient funds")) }
            val result: Either<CoreFailure, String> =
                either {
                    withError({ e: PaymentDeclined -> CoreFailure.Fatal(e.message) }) {
                        with(ctx) { fn("x") }
                    }
                }
            val failure = result.shouldBeLeft()
            failure.shouldBeInstanceOf<CoreFailure.Fatal>()
            failure.message shouldBe "declined: insufficient funds"
        }
    })

package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorMailboxTest :
    FunSpec({
        test("many one-way messages are processed in mailbox order") {
            runTest {
                val ref = spawn("mailbox-order-actor") { ctx -> MinimalActor(ctx) }

                (1..20).forEach { index ->
                    ref.tell(TestCommand.Record("v$index")).shouldBeSuccess(Unit)
                }
                advanceUntilIdle()

                ref
                    .ask(1.seconds) { replyTo -> TestCommand.Snapshot(replyTo) }
                    .shouldBeSuccess()
                    .shouldContainExactly((1..20).map { "v$it" })

                ref.shutdown()
            }
        }

        test("many ask messages complete correctly under load") {
            runTest {
                val ref = spawn("mailbox-ask-actor") { ctx -> MinimalActor(ctx) }

                val replies =
                    (1..20)
                        .map { index ->
                            async { ref.ask(1.seconds) { replyTo -> TestCommand.Ping("v$index", replyTo) } }
                        }.map { it.await().shouldBeSuccess() }

                replies shouldContainExactly (1..20).map { "echo: v$it" }
                ref.shutdown()
            }
        }

        test("queued requests fail as not delivered when an earlier command crashes") {
            runTest {
                val ref = spawn("crash-actor") { ctx -> MinimalActor(ctx) }

                val failureAsk = async { ref.ask(1.seconds) { replyTo -> TestCommand.Fail(replyTo) } }
                val pendingAsk = async { ref.ask(1.seconds) { replyTo -> TestCommand.Ping("after", replyTo) } }

                advanceUntilIdle()

                failureAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorCommandFailed>()
                val pendingCause = pendingAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>()
                pendingCause.reason shouldBe ActorUnavailableReason.NOT_DELIVERED
            }
        }

        test("forced shutdown drains pending requests as not delivered") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val ref = spawn("forced-shutdown-actor") { ctx -> MinimalActor(ctx) }

                ref.tell(TestCommand.Block(gate)).shouldBeSuccess(Unit)
                val pendingAsk = async { ref.ask(10.seconds) { replyTo -> TestCommand.Ping("queued", replyTo) } }

                runCurrent()
                val shutdown = async { ref.shutdown(50.milliseconds) }
                runCurrent()
                advanceTimeBy(50.milliseconds)
                runCurrent()

                val pendingCause = pendingAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>()
                pendingCause.reason shouldBe ActorUnavailableReason.NOT_DELIVERED

                gate.complete(Unit)
                shutdown.await()
            }
        }

        test("undelivered one-way commands are reported through the hook") {
            runTest {
                val undelivered = mutableListOf<String>()
                val ref = spawn("recording-actor") { ctx -> RecordingActor(ctx, undelivered) }

                ref.tell(TestCommand.Fail(deferredReplyChannel())).shouldBeSuccess(Unit)
                ref.tell(TestCommand.Record("dropped")).shouldBeSuccess(Unit)
                advanceUntilIdle()

                undelivered.shouldContainExactly("Record:NOT_DELIVERED")
                ref.tell(TestCommand.Record("late")).shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>()
            }
        }
    })

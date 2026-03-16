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
                val ref = spawn("mailbox-order-actor") { MinimalActor() }

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
                val ref = spawn("mailbox-ask-actor") { MinimalActor() }

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
                val ref = spawn("crash-actor") { MinimalActor() }

                val failureAsk = async { ref.ask(1.seconds) { replyTo -> TestCommand.Fail(replyTo) } }
                val pendingAsk = async { ref.ask(1.seconds) { replyTo -> TestCommand.Ping("after", replyTo) } }

                advanceUntilIdle()

                failureAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorCommandFailed>()
                val pendingCause = pendingAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>()
                pendingCause.reason shouldBe ActorUnavailableReason.NOT_DELIVERED
            }
        }

        test("cooperative shutdown waits for the current handler before dropping queued requests") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val ref = spawn("cooperative-shutdown-actor") { MinimalActor() }

                ref.tell(TestCommand.Block(gate)).shouldBeSuccess(Unit)
                val pendingAsk = async { ref.ask(10.seconds) { replyTo -> TestCommand.Ping("queued", replyTo) } }

                runCurrent()
                val shutdown = async { ref.shutdown(50.milliseconds) }
                runCurrent()
                advanceTimeBy(50.milliseconds)
                runCurrent()

                shutdown.isCompleted shouldBe false
                pendingAsk.isCompleted shouldBe false

                gate.complete(Unit)
                runCurrent()

                pendingAsk.await().shouldBeSuccess("echo: queued")
                shutdown.await()
            }
        }

        test("undelivered one-way commands are reported through the hook") {
            runTest {
                val undelivered = mutableListOf<String>()
                val ref = spawn("recording-actor") { RecordingActor(undelivered) }

                ref.tell(
                    TestCommand.Fail(
                        replyTo =
                            object : ReplyRef<String> {
                                override fun tell(reply: String): Result<Unit> = Result.success(Unit)
                            },
                    ),
                ).shouldBeSuccess(Unit)
                ref.tell(TestCommand.Record("dropped")).shouldBeSuccess(Unit)
                advanceUntilIdle()

                undelivered.shouldContainExactly("Record:NOT_DELIVERED")
                ref.tell(TestCommand.Record("late")).shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>()
            }
        }

        test("first shutdown timeout wins across concurrent callers") {
            runTest {
                val currentGate = CompletableDeferred<Unit>()
                val drainGate = CompletableDeferred<Unit>()
                val ref = spawn("shutdown-timeout-first-wins") { MinimalActor() }

                ref.tell(TestCommand.Block(currentGate)).shouldBeSuccess(Unit)
                ref.tell(TestCommand.Block(drainGate)).shouldBeSuccess(Unit)
                val pendingAsk = async { ref.ask(10.seconds) { replyTo -> TestCommand.Ping("after-drain", replyTo) } }

                runCurrent()
                val firstShutdown = async { ref.shutdown(50.milliseconds) }
                val secondShutdown = async { ref.shutdown(null) }

                currentGate.complete(Unit)
                runCurrent()
                advanceTimeBy(50.milliseconds)
                runCurrent()

                pendingAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>().reason shouldBe
                    ActorUnavailableReason.NOT_DELIVERED
                drainGate.complete(Unit)
                firstShutdown.await()
                secondShutdown.await()
            }
        }

        test("system watch notifications are processed even when the user mailbox is full") {
            runTest {
                val events = EventRecorder()
                val childRef = CompletableDeferred<ActorRef<ChildCommand>>()
                val gate = CompletableDeferred<Unit>()
                val ref =
                    spawn("system-priority-parent") {
                        ParentActor(events = events, childRef = childRef, capacity = 1)
                    }

                ref.tell(ParentCommand.Block(gate)).shouldBeSuccess(Unit)
                val snapshot = async { ref.ask(1.seconds) { replyTo -> ParentCommand.SnapshotEvents(replyTo) } }
                runCurrent()

                childRef.await().tell(ChildCommand.Fail).getOrThrow()
                gate.complete(Unit)
                advanceUntilIdle()

                val result = snapshot.await().shouldBeSuccess()
                result.filter { it.startsWith("parent:child-terminated:") }.size shouldBe 1

                ref.shutdown()
            }
        }
    })

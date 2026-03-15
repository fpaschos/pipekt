package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorTest :
    FunSpec({
        test("spawn waits for startup before returning a typed ref") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val startupGate = CompletableDeferred<Unit>()
                val spawnDeferred =
                    async {
                        // Launch spawn in a sibling coroutine so the test can observe whether
                        // startup blocks ref publication as intended.
                        spawn {
                            MinimalActor(
                                scope = scope,
                                name = "spawn-test-actor",
                                startupGate = startupGate,
                            )
                        }
                    }

                // Drive the scheduler until the actor is parked in postStart(). spawn() should
                // still be suspended on awaitStarted(), so no ref should escape yet.
                advanceUntilIdle()
                spawnDeferred.isCompleted shouldBe false

                startupGate.complete(Unit)
                val ref = spawnDeferred.await()

                ref.ask(1.seconds) { reply -> TestCommand.Ping("hello", reply) }.shouldBeSuccess("echo: hello")
                ref.shutdown()
            }
        }

        test("typed ref supports universal tell and ask") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = spawn { MinimalActor(scope, "tell-ask-actor") }

                ref.tell(TestCommand.Record("a")).shouldBeSuccess(Unit)
                ref.tell(TestCommand.Record("b")).shouldBeSuccess(Unit)
                // Flush the mailbox so Snapshot observes the effects of the earlier tell calls.
                advanceUntilIdle()

                ref.ask(1.seconds) { reply -> TestCommand.Ping("c", reply) }.shouldBeSuccess("echo: c")
                ref
                    .ask(1.seconds) { reply -> TestCommand.Snapshot(reply) }
                    .shouldBeSuccess()
                    .shouldContainExactly("a", "b")

                val commandFailure = ref.ask(1.seconds) { reply -> TestCommand.Fail(reply) }.shouldBeFailure()
                commandFailure.shouldBeInstanceOf<ActorCommandFailedException>()
                commandFailure.cause.shouldBeInstanceOf<IllegalStateException>()

                val closedAfterFailure =
                    ref.tell(TestCommand.Record("after-fail")).shouldBeFailure().shouldBeInstanceOf<ActorUnavailableException>()
                closedAfterFailure.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED

                val gate = CompletableDeferred<Unit>()
                val timeoutRef = spawn { MinimalActor(scope, "timeout-actor") }
                val timeoutFailure =
                    timeoutRef
                        .ask(50.milliseconds) { reply ->
                            // SlowPing blocks inside handle() so ask() must resolve via timeout,
                            // not by handler completion.
                            TestCommand.SlowPing("slow", gate, reply)
                        }.shouldBeFailure()
                timeoutFailure.shouldBeInstanceOf<ActorAskTimeoutException>()

                // Release the blocked handler so the actor can shut down cleanly after the
                // timeout assertion.
                gate.complete(Unit)
                advanceUntilIdle()
                timeoutRef.shutdown()
            }
        }

        test("actor startup and shutdown hooks run in sequence") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val events = mutableListOf<String>()
                val ref =
                    spawn {
                        MinimalActor(
                            scope = scope,
                            name = "lifecycle-actor",
                            events = events,
                        )
                    }

                ref.tell(TestCommand.Record("x")).shouldBeSuccess(Unit)
                // Ensure the record command is processed before shutdown so the lifecycle
                // sequence is deterministic for this assertion.
                advanceUntilIdle()
                ref.shutdown()

                events.shouldContainExactly(
                    "postStart:begin",
                    "postStart:end",
                    "handle:record:x",
                    "preStop",
                    "postStop",
                )

                val unavailable =
                    ref.tell(TestCommand.Record("late")).shouldBeFailure().shouldBeInstanceOf<ActorUnavailableException>()
                unavailable.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED
            }
        }

        test("pending ask is failed as not delivered when actor crashes on earlier command") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = spawn { MinimalActor(scope, "crash-actor") }

                val failureAsk =
                    async {
                        // This command is expected to fail inside handle(), which should now
                        // stop the actor instead of allowing later commands to run.
                        ref.ask(1.seconds) { reply -> TestCommand.Fail(reply) }
                    }
                val pendingAsk =
                    async {
                        // This ask is enqueued behind the failing command. It should never reach
                        // handle(); the actor should fail it as NOT_DELIVERED during teardown.
                        ref.ask(1.seconds) { reply -> TestCommand.Ping("after", reply) }
                    }

                // Run both enqueues and the ensuing actor failure to completion.
                advanceUntilIdle()

                val failCause = failureAsk.await().shouldBeFailure()
                failCause.shouldBeInstanceOf<ActorCommandFailedException>()

                val pendingCause = pendingAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailableException>()
                pendingCause.reason shouldBe ActorUnavailableReason.NOT_DELIVERED
            }
        }

        test("forced shutdown marks pending ask as not delivered") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val gate = CompletableDeferred<Unit>()
                val ref = spawn { MinimalActor(scope, "forced-shutdown-actor") }

                ref.tell(TestCommand.Block(gate)).shouldBeSuccess(Unit)
                val pendingAsk =
                    async {
                        // Queue a reply-bearing command behind a handler that is blocked. The
                        // later forced shutdown should fail this pending ask as NOT_DELIVERED.
                        ref.ask(10.seconds) { reply -> TestCommand.Ping("queued", reply) }
                    }

                // runCurrent() advances only the currently runnable work. This keeps virtual
                // time from jumping to the ask timeout before shutdown gets a chance to fire.
                runCurrent()
                val shutdown = async { ref.shutdown(50.milliseconds) }
                runCurrent()
                // Advance exactly through the shutdown timeout budget so the force-stop path
                // executes and drains undelivered commands.
                advanceTimeBy(50.milliseconds)
                runCurrent()

                val pendingCause = pendingAsk.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailableException>()
                pendingCause.reason shouldBe ActorUnavailableReason.NOT_DELIVERED

                gate.complete(Unit)
                shutdown.await()
            }
        }

        test("undelivered one-way commands are reported through the hook") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val undelivered = mutableListOf<String>()
                val ref = spawn { RecordingActor(scope, "recording-actor", undelivered) }

                ref.tell(TestCommand.Fail(CompletableDeferred())).shouldBeSuccess(Unit)
                ref.tell(TestCommand.Record("dropped")).shouldBeSuccess(Unit)

                // The first command crashes the actor; the second one was accepted earlier but
                // must be surfaced via the undelivered hook rather than silently disappearing.
                advanceUntilIdle()

                undelivered.shouldContainExactly("Record:NOT_DELIVERED")
                val closed =
                    ref.tell(TestCommand.Record("late")).shouldBeFailure().shouldBeInstanceOf<ActorUnavailableException>()
                closed.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED
            }
        }
    })

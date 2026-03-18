package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorLifecycleTest :
    FunSpec({
        test("spawn waits for postStart before publishing the ref") {
            runTest {
                val startupGate = CompletableDeferred<Unit>()
                val refDeferred = CompletableDeferred<ActorRef<TestCommand>>()

                val owner =
                    backgroundScope.launch {
                        refDeferred.complete(
                            spawn("startup-actor") {
                                MinimalActor(startupGate = startupGate)
                            },
                        )
                    }

                advanceUntilIdle()
                refDeferred.isCompleted shouldBe false

                startupGate.complete(Unit)
                val ref = refDeferred.await()
                ref.tell(TestCommand.Record("ready")).shouldBeSuccess(Unit)
                ref.shutdown()
                owner.join()
            }
        }

        test("startup and shutdown hooks run in sequence") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("lifecycle-actor") { MinimalActor(events = events) }

                ref.tell(TestCommand.Record("x")).shouldBeSuccess(Unit)
                advanceUntilIdle()
                ref.shutdown()

                events.snapshot().shouldContainExactly(
                    "postStart:begin",
                    "postStart:end",
                    "handle:record:x",
                    "preStop",
                    "postStop",
                )
            }
        }

        test("parent actor shutdown can explicitly stop watched actors before termination completes") {
            runTest {
                val events = EventRecorder()
                val childRef = CompletableDeferred<ActorRef<ChildCommand>>()
                val ref = spawn("parent-actor") { ParentActor(events, childRef = childRef) }

                ref.shutdown()
                advanceUntilIdle()

                events.snapshot().shouldContain("child:postStop")
            }
        }

        test("startup failure does not publish a half-started ref") {
            runTest {
                val failure =
                    runCatching {
                        spawn("failing-start") {
                            FailingStartActor(IllegalStateException("startup-boom"))
                        }
                    }.exceptionOrNull()

                failure.shouldBeInstanceOf<IllegalStateException>()
            }
        }

        test("concurrent shutdown callers share the same termination path") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val ref = spawn("concurrent-shutdown-actor") { MinimalActor() }

                ref.tell(TestCommand.Block(gate)).shouldBeSuccess(Unit)
                advanceUntilIdle()

                val first = async { ref.shutdown() }
                val second = async { ref.shutdown() }

                gate.complete(Unit)
                advanceUntilIdle()

                first.await()
                second.await()
            }
        }

        test("ctx stopSelf requests shutdown without deadlocking") {
            runTest {
                val ref = spawn("self-stop-actor") { MinimalActor() }

                ref.tell(TestCommand.StopSelf).shouldBeSuccess(Unit)
                advanceUntilIdle()

                ref.tell(TestCommand.Record("late")).exceptionOrNull().shouldBeInstanceOf<ActorUnavailable>()
            }
        }

        test("accepted asks do not become ActorCommandFailed when parent cancellation stops the actor") {
            runTest {
                val actorRef = CompletableDeferred<ActorRef<TestCommand>>()
                val owner =
                    backgroundScope.launch {
                        val ref = spawn("cancelled-ask-actor") { MinimalActor() }
                        actorRef.complete(ref)
                    }

                val ref = actorRef.await()
                advanceUntilIdle()
                val askResult = async { ref.ask(10.seconds) { replyTo -> TestCommand.CancelCurrent(replyTo) } }
                owner.cancelAndJoin()
                advanceUntilIdle()

                val failure = askResult.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>()
                failure.reason shouldBe ActorUnavailableReason.NOT_DELIVERED
            }
        }

        test("cancellation still runs preStop and postStop under NonCancellable") {
            runTest {
                val events = EventRecorder()
                val gate = CompletableDeferred<Unit>()
                val actorRef = CompletableDeferred<ActorRef<TestCommand>>()
                val owner =
                    backgroundScope.launch {
                        actorRef.complete(
                            spawn("cleanup-on-cancel") {
                                CancellationCleanupActor(events, gate)
                            },
                        )
                    }

                val ref = actorRef.await()
                ref.tell(TestCommand.Block(gate)).shouldBeSuccess(Unit)
                advanceUntilIdle()

                owner.cancelAndJoin()
                advanceUntilIdle()

                events.snapshot().shouldContain("preStop:begin")
                events.snapshot().shouldContain("preStop:end")
                events.snapshot().shouldContain("postStop:begin")
                events.snapshot().shouldContain("postStop:end")
                ref.tell(TestCommand.Record("late")).exceptionOrNull().shouldBeInstanceOf<ActorUnavailable>()
            }
        }

        test("teardown failures do not overwrite an existing terminal cause") {
            runTest {
                val events = EventRecorder()
                val ref =
                    spawn("failing-teardown") {
                        FailingTeardownActor(
                            events = events,
                            gate = CompletableDeferred(),
                            failPreStop = true,
                            failPostStop = true,
                        )
                    }

                val failure = ref.ask(1.seconds) { replyTo -> TestCommand.Fail(replyTo) }.shouldBeFailure()
                val commandFailure = failure.shouldBeInstanceOf<ActorCommandFailed>()
                commandFailure.cause.shouldBeInstanceOf<IllegalStateException>()
                commandFailure.cause!!.message shouldBe "primary-boom"
                events.snapshot().shouldContainExactly("preStop", "postStop")
            }
        }
    })

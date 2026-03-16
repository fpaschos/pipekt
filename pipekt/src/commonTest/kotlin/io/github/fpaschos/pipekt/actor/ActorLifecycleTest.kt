package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

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
    })

package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ActorLifecycleTest :
    FunSpec({
        test("spawn waits for postStart before publishing the ref") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val startupGate = CompletableDeferred<Unit>()

                val spawnDeferred =
                    async {
                        spawn {
                            MinimalActor(
                                scope = scope,
                                name = "startup-actor",
                                startupGate = startupGate,
                            )
                        }
                    }

                advanceUntilIdle()
                spawnDeferred.isCompleted shouldBe false

                startupGate.complete(Unit)
                val ref = spawnDeferred.await()
                ref.tell(TestCommand.Record("ready")).shouldBeSuccess(Unit)
                ref.shutdown()
            }
        }

        test("shutdown during startup fails startup cleanly") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val startupGate = CompletableDeferred<Unit>()
                val actor = MinimalActor(scope, "shutdown-during-startup", startupGate = startupGate)

                val shutdown = async { actor.self().shutdown() }
                val startupFailure = async { runCatching { actor.awaitStarted() } }

                advanceUntilIdle()
                startupGate.complete(Unit)
                advanceUntilIdle()

                startupFailure.await().exceptionOrNull().shouldBeInstanceOf<CancellationException>()
                shutdown.await()
                actor.awaitTerminated()
            }
        }

        test("startup and shutdown hooks run in sequence") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val events = mutableListOf<String>()
                val ref = spawn { MinimalActor(scope, "lifecycle-actor", events = events) }

                ref.tell(TestCommand.Record("x")).shouldBeSuccess(Unit)
                advanceUntilIdle()
                ref.shutdown()

                events.shouldContainExactly(
                    "postStart:begin",
                    "postStart:end",
                    "handle:record:x",
                    "preStop",
                    "postStop",
                )
            }
        }

        test("startup failure does not publish a half-started ref") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())

                val failure =
                    runCatching {
                        spawn {
                            FailingStartActor(scope, "failing-start", IllegalStateException("startup-boom"))
                        }
                    }.exceptionOrNull()

                failure.shouldBeInstanceOf<IllegalStateException>()
            }
        }
    })

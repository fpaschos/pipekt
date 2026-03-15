package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorSpawnScopeTest :
    FunSpec({
        test("spawn keeps semantic names distinct from labels") {
            runTest {
                val first =
                    spawn("shared-name") { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }
                val second =
                    spawn("shared-name") { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }

                first.name shouldBe "shared-name"
                second.name shouldBe "shared-name"
                first.label shouldNotBe second.label

                first.shutdown()
                second.shutdown()
            }
        }

        test("spawned actor loop uses the generated actor label as coroutine name") {
            runTest {
                val ref =
                    spawn("loop-name-actor") { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }

                ref.ask(1.seconds) { replyTo -> TestCommand.LoopName(replyTo) }.shouldBeSuccess(ref.label)

                ref.shutdown()
            }
        }

        test("ambient parent cancellation terminates spawned actors") {
            runTest {
                val actorRef = CompletableDeferred<ActorRef<TestCommand>>()
                val ownerJob: Job =
                    backgroundScope.launch {
                        actorRef.complete(
                            spawn("cancellable-actor") { actorScope, actorName ->
                                MinimalActor(actorScope, actorName)
                            },
                        )
                    }

                val ref = actorRef.await()
                ownerJob.cancelAndJoin()
                advanceUntilIdle()

                val failure =
                    ref.tell(TestCommand.Record("late")).exceptionOrNull().shouldBeInstanceOf<ActorUnavailable>()
                failure.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED
            }
        }

        test("dispatcher override does not break ambient parent ownership") {
            runTest {
                val overrideDispatcher = StandardTestDispatcher(testScheduler)
                val actorRef = CompletableDeferred<ActorRef<TestCommand>>()
                val ownerJob: Job =
                    backgroundScope.launch {
                        actorRef.complete(
                            spawn(
                                name = "override-actor",
                                dispatcher = overrideDispatcher,
                            ) { actorScope, actorName ->
                                MinimalActor(actorScope, actorName)
                            },
                        )
                    }

                val ref = actorRef.await()
                ownerJob.cancelAndJoin()
                advanceUntilIdle()

                val failure =
                    ref.tell(TestCommand.Record("late")).exceptionOrNull().shouldBeInstanceOf<ActorUnavailable>()
                failure.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED
            }
        }
    })

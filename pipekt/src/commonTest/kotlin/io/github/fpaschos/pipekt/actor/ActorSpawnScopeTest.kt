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
                    spawn("shared-name") { ctx ->
                        MinimalActor(ctx)
                    }
                val second =
                    spawn("shared-name") { ctx ->
                        MinimalActor(ctx)
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
                    spawn("loop-name-actor") { ctx ->
                        MinimalActor(ctx)
                    }

                ref.ask(1.seconds) { replyTo -> TestCommand.LoopName(replyTo) }.shouldBeSuccess(ref.label)

                ref.shutdown()
            }
        }

        test("ctx self exposes the same name and label as the published ref") {
            runTest {
                val selfRef = CompletableDeferred<ActorRef<TestCommand>>()
                val ref =
                    spawn("self-check") { ctx ->
                        selfRef.complete(ctx.self)
                        MinimalActor(ctx)
                    }

                val fromContext = selfRef.await()
                fromContext.name shouldBe ref.name
                fromContext.label shouldBe ref.label

                ref.shutdown()
            }
        }

        test("ambient parent cancellation terminates spawned actors") {
            runTest {
                val actorRef = CompletableDeferred<ActorRef<TestCommand>>()
                val ownerJob: Job =
                    backgroundScope.launch {
                        actorRef.complete(
                            spawn("cancellable-actor") { ctx ->
                                MinimalActor(ctx)
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
                            ) { ctx ->
                                MinimalActor(ctx)
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

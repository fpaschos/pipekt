package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorSpawnScopeTest :
    FunSpec({
        test("spawn can inherit a parent scope and keep semantic names distinct from labels") {
            runTest(StandardTestDispatcher()) {
                val parentScope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("parent-scope"))

                val first =
                    spawn(parentScope, "shared-name") { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }
                val second =
                    spawn(parentScope, "shared-name") { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }

                first.actorName shouldBe "shared-name"
                second.actorName shouldBe "shared-name"
                first.actorLabel shouldNotBe second.actorLabel

                first.shutdown()
                second.shutdown()
                parentScope.cancel()
            }
        }

        test("spawned actor loop uses the generated actor label as coroutine name") {
            runTest(StandardTestDispatcher()) {
                val parentScope = CoroutineScope(coroutineContext + SupervisorJob())
                val ref =
                    spawn(parentScope, "loop-name-actor") { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }

                ref.ask(1.seconds) { replyTo -> TestCommand.LoopName(replyTo) }.shouldBeSuccess(ref.actorLabel)

                ref.shutdown()
                parentScope.cancel()
            }
        }

        test("parent cancellation terminates actors spawned from the inherited scope overload") {
            runTest(StandardTestDispatcher()) {
                val parentScope = CoroutineScope(coroutineContext + SupervisorJob())
                val ref =
                    spawn(parentScope, "cancellable-actor") { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }

                parentScope.cancel()
                advanceUntilIdle()

                val failure =
                    ref.tell(TestCommand.Record("late")).exceptionOrNull().shouldBeInstanceOf<ActorUnavailableException>()
                failure.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED
            }
        }

        test("dispatcher override does not break parent ownership") {
            runTest(StandardTestDispatcher()) {
                val parentScope = CoroutineScope(coroutineContext + SupervisorJob())
                val overrideDispatcher = StandardTestDispatcher(testScheduler)
                val ref =
                    spawn(
                        parentScope = parentScope,
                        actorName = "override-actor",
                        dispatcher = overrideDispatcher,
                    ) { actorScope, actorName ->
                        MinimalActor(actorScope, actorName)
                    }

                parentScope.cancel()
                advanceUntilIdle()

                val failure =
                    ref.tell(TestCommand.Record("late")).exceptionOrNull().shouldBeInstanceOf<ActorUnavailableException>()
                failure.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED
            }
        }
    })

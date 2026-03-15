package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
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
class ActorConcurrencyRegressionTest :
    FunSpec({
        test("concurrent shutdown callers share the same termination path") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val gate = CompletableDeferred<Unit>()
                val ref = spawn { MinimalActor(scope, "concurrent-shutdown-actor") }

                ref.tell(TestCommand.Block(gate)).shouldBeSuccess(Unit)
                runCurrent()

                val first = async { ref.shutdown(50.milliseconds) }
                val second = async { ref.shutdown(50.milliseconds) }

                advanceTimeBy(50.milliseconds)
                gate.complete(Unit)
                runCurrent()

                first.await()
                second.await()
            }
        }

        test("early termination during startup leaves no usable ref") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val startupGate = CompletableDeferred<Unit>()
                val actor = MinimalActor(scope, "early-termination-actor", startupGate = startupGate)

                val startup = async { runCatching { actor.awaitStarted() } }
                val shutdown = async { actor.self().shutdown() }

                advanceUntilIdle()
                startupGate.complete(Unit)
                advanceUntilIdle()

                startup.await().exceptionOrNull().shouldBeInstanceOf<Throwable>()
                shutdown.await()
            }
        }

        test("high-volume queued requests fail as not delivered after a crash") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val ref = spawn { MinimalActor(scope, "high-volume-crash-actor") }

                val failure = async { ref.ask(1.seconds) { replyTo -> TestCommand.Fail(replyTo) } }
                val pending =
                    (1..25).map { index ->
                        async { ref.ask(1.seconds) { replyTo -> TestCommand.Ping("p$index", replyTo) } }
                    }

                advanceUntilIdle()

                failure.await().shouldBeFailure().shouldBeInstanceOf<ActorCommandFailedException>()
                pending.forEach { deferred ->
                    val cause = deferred.await().shouldBeFailure().shouldBeInstanceOf<ActorUnavailableException>()
                    cause.reason shouldBe ActorUnavailableReason.NOT_DELIVERED
                }
            }
        }
    })

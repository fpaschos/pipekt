package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Basic actor infrastructure tests: spawn/startup, command handling, and shutdown.
 * Uses a minimal [MinimalActor] that echoes a string via request/reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActorTest :
    FunSpec({

        test("spawn suspends until actor is running") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = MinimalActor.spawn(scope)
                ref.ping("hello") shouldBe "echo: hello"
                ref.shutdown(null)
            }
        }

        test("actor handles multiple requests before shutdown") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = MinimalActor.spawn(scope)
                ref.ping("a") shouldBe "echo: a"
                ref.ping("b") shouldBe "echo: b"
                ref.shutdown(null)
            }
        }

        test("shutdown terminates actor and subsequent ping fails") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = MinimalActor.spawn(scope)
                ref.shutdown(null)
                runCatching { ref.ping("late") }.isFailure shouldBe true
            }
        }

        test("second shutdown is idempotent") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = MinimalActor.spawn(scope)
                ref.shutdown(null)
                ref.shutdown(null)
                ref.shutdown(1.seconds)
            }
        }

        test("request failure completes exceptionally instead of hanging") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = MinimalActor.spawn(scope)
                val failure = runCatching { ref.fail() }.exceptionOrNull()
                failure.shouldBeInstanceOf<IllegalStateException>()
                ref.shutdown(null)
            }
        }

        test("shutdown during startup fails startup and terminates actor") {
            runTest(StandardTestDispatcher()) {
                val gate = CompletableDeferred<Unit>()
                val actor = StartupActor(this, gate)
                val startup = async { actor.awaitStarted() }

                advanceUntilIdle()
                val shutdown = launch { actor.shutdownInternal(null) }
                advanceUntilIdle()

                gate.complete(Unit)
                advanceUntilIdle()

                runCatching { startup.await() }.isFailure shouldBe true
                shutdown.join()
                actor.awaitTerminated()
            }
        }

        test("concurrent shutdown callers share the same termination") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = MinimalActor.spawn(scope)

                val first = async { ref.shutdown(null) }
                val second = async { ref.shutdown(null) }

                first.await()
                second.await()
            }
        }
    })

private class StartupActor(
    scope: CoroutineScope,
    private val gate: CompletableDeferred<Unit>,
) : Actor<Unit>(scope, "startup-actor") {
    override suspend fun handle(command: Unit) = Unit

    override suspend fun postStart() {
        gate.await()
    }

    suspend fun shutdownInternal(timeout: Duration?) {
        shutdown(timeout = timeout)
    }
}

package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration

/**
 * Basic actor infrastructure tests: spawn/startup, command handling, and shutdown.
 * Uses a minimal [MinimalActor] that echoes a string via request/reply.
 */
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
                ref.shutdown(Duration.parse("1s"))
            }
        }
    })

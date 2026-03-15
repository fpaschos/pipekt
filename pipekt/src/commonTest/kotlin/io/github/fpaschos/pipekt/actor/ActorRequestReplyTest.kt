package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorRequestReplyTest :
    FunSpec({
        test("typed refs support tell and ask through the shared reply channel") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val ref = spawn { MinimalActor(scope, "tell-ask-actor") }

                ref.tell(TestCommand.Record("a")).shouldBeSuccess(Unit)
                ref.tell(TestCommand.Record("b")).shouldBeSuccess(Unit)
                advanceUntilIdle()

                ref.ask(1.seconds) { replyTo -> TestCommand.Ping("c", replyTo) }.shouldBeSuccess("echo: c")
                ref
                    .ask(1.seconds) { replyTo -> TestCommand.Snapshot(replyTo) }
                    .shouldBeSuccess()
                    .shouldContainExactly("a", "b")

                ref.shutdown()
            }
        }

        test("handler exceptions surface as command failures") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val ref = spawn { MinimalActor(scope, "failure-actor") }

                val failure = ref.ask(1.seconds) { replyTo -> TestCommand.Fail(replyTo) }.shouldBeFailure()
                failure.shouldBeInstanceOf<ActorCommandFailedException>()
            }
        }

        test("ask timeouts return ActorAskTimeoutException") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val gate = CompletableDeferred<Unit>()
                val ref = spawn { MinimalActor(scope, "timeout-actor") }

                val failure =
                    ref
                        .ask(50.milliseconds) { replyTo ->
                            TestCommand.SlowPing("slow", gate, replyTo)
                        }.shouldBeFailure()

                failure.shouldBeInstanceOf<ActorAskTimeoutException>()

                gate.complete(Unit)
                advanceUntilIdle()
                ref.shutdown()
            }
        }
    })

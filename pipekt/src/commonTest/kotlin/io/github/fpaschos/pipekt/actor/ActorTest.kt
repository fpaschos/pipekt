package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorTest :
    FunSpec({
        test("spawn waits for startup before returning a typed ref") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val startupGate = CompletableDeferred<Unit>()
                val spawnDeferred =
                    async {
                        spawn {
                            MinimalActor(
                                scope = scope,
                                name = "spawn-test-actor",
                                startupGate = startupGate,
                            )
                        }
                    }

                advanceUntilIdle()
                spawnDeferred.isCompleted shouldBe false

                startupGate.complete(Unit)
                val ref = spawnDeferred.await()

                ref.ask(1.seconds) { reply -> TestCommand.Ping("hello", reply) }.shouldBeSuccess("echo: hello")
                ref.shutdown()
            }
        }

        test("typed ref supports universal tell and ask") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val ref = spawn { MinimalActor(scope, "tell-ask-actor") }

                ref.tell(TestCommand.Record("a")).shouldBeSuccess(Unit)
                ref.tell(TestCommand.Record("b")).shouldBeSuccess(Unit)
                advanceUntilIdle()

                ref.ask(1.seconds) { reply -> TestCommand.Ping("c", reply) }.shouldBeSuccess("echo: c")
                ref
                    .ask(1.seconds) { reply -> TestCommand.Snapshot(reply) }
                    .shouldBeSuccess()
                    .shouldContainExactly("a", "b")

                val commandFailure = ref.ask(1.seconds) { reply -> TestCommand.Fail(reply) }.shouldBeFailure()
                commandFailure.shouldBeInstanceOf<ActorCommandFailed>()
                commandFailure.cause.shouldBeInstanceOf<IllegalStateException>()

                val gate = CompletableDeferred<Unit>()
                val timeoutFailure =
                    ref
                        .ask(50.milliseconds) { reply ->
                            TestCommand.SlowPing("slow", gate, reply)
                        }.shouldBeFailure()
                timeoutFailure.shouldBeInstanceOf<ActorAskTimeout>()

                gate.complete(Unit)
                advanceUntilIdle()
                ref.shutdown()
            }
        }

        test("actor startup and shutdown hooks run in sequence") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("test-actor-scope"))
                val events = mutableListOf<String>()
                val ref =
                    spawn {
                        MinimalActor(
                            scope = scope,
                            name = "lifecycle-actor",
                            events = events,
                        )
                    }

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

                val unavailable = ref.tell(TestCommand.Record("late")).shouldBeFailure().shouldBeInstanceOf<ActorUnavailable>()
                unavailable.reason shouldBe ActorUnavailableReason.ACTOR_CLOSED
            }
        }
    })

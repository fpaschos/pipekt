package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorWatchTest :
    FunSpec({
        test("watched actor normal termination emits one parent self-message") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("watch-parent") { ParentActor(events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.StopChild(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.snapshot().filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(1)
                childEvents.single().shouldContain(":normal")

                ref.shutdown()
            }
        }

        test("watched actor failure emits one parent self-message with the cause") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("watch-failure-parent") { ParentActor(events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.FailChild(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.snapshot().filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(1)
                childEvents.single().shouldContain("child-boom")

                ref.shutdown()
            }
        }

        test("watching an already terminated actor emits immediate notification") {
            runTest {
                val events = EventRecorder()
                val childRef = CompletableDeferred<ActorRef<ChildCommand>>()
                val ref = spawn("watch-stopped-parent") { ParentActor(events, childRef = childRef) }

                val child = childRef.await()
                child.shutdown()
                advanceUntilIdle()

                ref.ask(1.seconds) { replyTo -> ParentCommand.WatchStopped(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.snapshot().filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(2)

                ref.shutdown()
            }
        }

        test("watching a foreign actor ref fails explicitly") {
            runTest {
                val foreignRef =
                    object : ActorRef<Any> {
                        override val name: String = "foreign"
                        override val label: String = "foreign#1"

                        override fun tell(command: Any): Result<Unit> = Result.success(Unit)

                        override suspend fun shutdown(timeout: kotlin.time.Duration?) = Unit
                    }

                val failure =
                    runCatching {
                        spawn("foreign-watch") { ForeignWatchingActor(foreignRef) }
                    }.exceptionOrNull()

                failure.shouldNotBe(null)
                failure!!.message.shouldContain("Only actor refs created by this runtime can be watched")
            }
        }
    })

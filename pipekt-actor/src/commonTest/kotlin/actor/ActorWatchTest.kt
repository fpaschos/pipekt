package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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

        test("duplicate watch registration for a live actor is idempotent") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("watch-idempotent-parent") { ParentActor(events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.WatchActive(replyTo) }.getOrThrow()
                ref.ask(1.seconds) { replyTo -> ParentCommand.FailChild(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.snapshot().filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(1)

                ref.shutdown()
            }
        }

        test("actor-to-actor replyTo can target another actor ref directly") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("watch-child-reply-parent") { ParentActor(events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.TriggerChildReply(replyTo) }.getOrThrow()
                advanceUntilIdle()

                events.snapshot().filter { it == "parent:child-reply" }.shouldHaveSize(1)
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

        test("watching self fails explicitly") {
            runTest {
                val failure =
                    runCatching {
                        spawn("self-watch") { SelfWatchingActor() }
                    }.exceptionOrNull()

                failure.shouldNotBe(null)
                failure!!.message.shouldContain("cannot watch itself")
            }
        }

        test("watch registration fails once the watcher is shutting down") {
            runTest {
                val watched = spawn("watch-target") { MinimalActor() }
                val watchFailure = CompletableDeferred<Throwable>()
                val watcher = spawn("watch-during-shutdown") { WatchDuringShutdownActor(watched, watchFailure) }

                watcher.shutdown()
                val failure = watchFailure.await()

                failure.message.shouldContain("cannot register new watches while shutting down")
                watched.shutdown()
            }
        }
    })

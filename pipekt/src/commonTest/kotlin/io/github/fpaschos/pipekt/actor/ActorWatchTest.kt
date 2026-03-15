package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorWatchTest :
    FunSpec({
        test("watched child normal termination emits one parent self-message") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("watch-parent") { ctx -> ParentActor(ctx, events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.StopChild(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.snapshot().filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(1)
                childEvents.single().shouldContain(":normal")

                ref.shutdown()
            }
        }

        test("watched child failure emits one parent self-message with the cause") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("watch-failure-parent") { ctx -> ParentActor(ctx, events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.FailChild(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.snapshot().filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(1)
                childEvents.single().shouldContain("child-boom")

                ref.shutdown()
            }
        }
    })

package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorWatchTest :
    FunSpec({
        test("watched child normal termination emits one parent self-message") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val events = mutableListOf<String>()
                val ref = spawn { ParentActor(scope, "watch-parent", events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.StopChild(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(1)
                childEvents.single().shouldContain(":normal")

                ref.shutdown()
            }
        }

        test("watched child failure emits one parent self-message with the cause") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val events = mutableListOf<String>()
                val ref = spawn { ParentActor(scope, "watch-failure-parent", events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.FailChild(replyTo) }.getOrThrow()
                advanceUntilIdle()

                val childEvents = events.filter { it.startsWith("parent:child-terminated:") }
                childEvents.shouldHaveSize(1)
                childEvents.single().shouldContain("child-boom")

                ref.shutdown()
            }
        }
    })

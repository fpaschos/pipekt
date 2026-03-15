package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorChildOwnershipTest :
    FunSpec({
        test("parent shutdown stops owned children before termination completes") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val events = mutableListOf<String>()
                val ref = spawn { ParentActor(scope, "parent-actor", events) }

                ref.shutdown()
                advanceUntilIdle()

                events.shouldContain("child:postStop")
            }
        }

        test("child failure does not automatically crash the parent") {
            runTest(StandardTestDispatcher()) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val events = mutableListOf<String>()
                val ref = spawn { ParentActor(scope, "parent-survives-child", events) }

                ref.ask(1.seconds) { replyTo -> ParentCommand.FailChild(replyTo) }.shouldBeSuccess(Unit)
                advanceUntilIdle()

                ref
                    .ask(1.seconds) { replyTo -> ParentCommand.SnapshotEvents(replyTo) }
                    .getOrThrow()
                    .any { it.contains("child-boom") } shouldBe true

                ref.shutdown()
            }
        }
    })

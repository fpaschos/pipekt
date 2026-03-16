package io.github.fpaschos.pipekt.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorChildOwnershipTest :
    FunSpec({
        test("parent actor shutdown can explicitly stop watched actors before termination completes") {
            runTest {
                val events = EventRecorder()
                val childRef = CompletableDeferred<ActorRef<ChildCommand>>()
                val ref = spawn("parent-actor") { ParentActor(events, childRef = childRef) }

                ref.shutdown()
                advanceUntilIdle()

                events.snapshot().shouldContain("child:postStop")
            }
        }

        test("watched actor failure does not automatically crash the watcher") {
            runTest {
                val events = EventRecorder()
                val ref = spawn("parent-survives-child") { ParentActor(events) }

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

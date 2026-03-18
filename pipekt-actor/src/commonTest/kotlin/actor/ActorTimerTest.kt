package io.github.fpaschos.pipekt.actor

import io.github.fpaschos.pipekt.fixtures.TimerActor
import io.github.fpaschos.pipekt.fixtures.TimerCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ActorTimerTest :
    FunSpec({
        test("once delivers one delayed self-message") {
            runTest {
                val ref = spawn("timer-once") { TimerActor() }

                ref
                    .ask(1.seconds) { replyTo ->
                        TimerCommand.StartOnce(
                            key = TimerKey("once"),
                            delay = 100.milliseconds,
                            value = "once",
                            replyTo = replyTo,
                        )
                    }.getOrThrow()

                advanceTimeBy(100.milliseconds)
                advanceUntilIdle()

                ref
                    .ask(1.seconds) { replyTo -> TimerCommand.Snapshot(replyTo) }
                    .getOrThrow()
                    .shouldContainExactly("once")

                ref.shutdown()
            }
        }

        test("reusing a key replaces an already queued once timer message") {
            runTest {
                val ref = spawn("timer-replace") { TimerActor() }

                ref
                    .ask(1.seconds) { replyTo ->
                        TimerCommand.ReplaceQueuedOnce(
                            key = TimerKey("replace"),
                            firstDelay = 50.milliseconds,
                            replacementDelay = 100.milliseconds,
                            pauseBeforeReplace = 75.milliseconds,
                            firstValue = "old",
                            replacementValue = "new",
                            replyTo = replyTo,
                        )
                    }.getOrThrow()

                advanceTimeBy(200.milliseconds)
                advanceUntilIdle()

                ref
                    .ask(1.seconds) { replyTo -> TimerCommand.Snapshot(replyTo) }
                    .getOrThrow()
                    .shouldContainExactly("new")

                ref.shutdown()
            }
        }

        test("cancel suppresses an already queued once timer message") {
            runTest {
                val ref = spawn("timer-cancel") { TimerActor() }

                ref
                    .ask(1.seconds) { replyTo ->
                        TimerCommand.CancelQueuedOnce(
                            key = TimerKey("cancel"),
                            delay = 50.milliseconds,
                            pauseBeforeCancel = 75.milliseconds,
                            value = "old",
                            replyTo = replyTo,
                        )
                    }.getOrThrow()

                advanceTimeBy(200.milliseconds)
                advanceUntilIdle()

                ref
                    .ask(1.seconds) { replyTo -> TimerCommand.Snapshot(replyTo) }
                    .getOrThrow()
                    .shouldContainExactly(emptyList())

                ref.shutdown()
            }
        }

        test("repeated uses fixed-delay cadence") {
            runTest {
                val ref = spawn("timer-repeated") { TimerActor() }

                ref
                    .ask(1.seconds) { replyTo ->
                        TimerCommand.StartRepeated(
                            key = TimerKey("repeat"),
                            interval = 100.milliseconds,
                            value = "tick",
                            replyTo = replyTo,
                        )
                    }.getOrThrow()

                advanceTimeBy(350.milliseconds)
                runCurrent()

                ref
                    .ask(1.seconds) { replyTo -> TimerCommand.Snapshot(replyTo) }
                    .getOrThrow()
                    .shouldContainExactly("tick", "tick", "tick")

                ref.shutdown()
            }
        }

        test("timers are canceled when the actor shuts down") {
            runTest {
                val events = mutableListOf<String>()
                val ref = spawn("timer-shutdown") { TimerActor(events) }

                ref
                    .ask(1.seconds) { replyTo ->
                        TimerCommand.StartOnce(
                            key = TimerKey("shutdown"),
                            delay = 100.milliseconds,
                            value = "late",
                            replyTo = replyTo,
                        )
                    }.getOrThrow()
                ref.shutdown()

                advanceTimeBy(200.milliseconds)
                advanceUntilIdle()

                events shouldBe emptyList()
            }
        }

        test("timer APIs fail when used outside the actor loop") {
            runTest {
                val ref = spawn("timer-leak") { TimerActor() }

                val leakedTimers =
                    ref
                        .ask(1.seconds) { replyTo -> TimerCommand.LeakTimers(replyTo) }
                        .getOrThrow()

                val failure =
                    async {
                        runCatching {
                            leakedTimers.once(TimerKey("leaked"), ZERO, TimerCommand.Record("boom"))
                        }.exceptionOrNull()
                    }.await()

                failure?.message.shouldContain("only within actor")
                ref.shutdown()
            }
        }
    })

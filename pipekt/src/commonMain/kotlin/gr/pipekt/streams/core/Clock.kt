package gr.pipekt.streams.core

fun interface Clock {
    fun nowMs(): Long
}

expect fun systemClock(): Clock

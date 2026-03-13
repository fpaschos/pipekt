package io.github.fpaschos.pipekt.core

fun interface Clock {
    fun nowMs(): Long
}

expect fun systemClock(): Clock

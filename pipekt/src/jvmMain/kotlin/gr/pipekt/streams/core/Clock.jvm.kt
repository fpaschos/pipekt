package gr.pipekt.streams.core

actual fun systemClock(): Clock = Clock { System.currentTimeMillis() }

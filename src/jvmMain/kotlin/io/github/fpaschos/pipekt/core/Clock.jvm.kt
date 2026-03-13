package io.github.fpaschos.pipekt.core

actual fun systemClock(): Clock = Clock { System.currentTimeMillis() }

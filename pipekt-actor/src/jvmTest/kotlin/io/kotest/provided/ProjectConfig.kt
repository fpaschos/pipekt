package io.kotest.provided

import io.github.smyrgeorge.log4k.Level
import io.github.smyrgeorge.log4k.RootLogger
import io.kotest.core.config.AbstractProjectConfig

object ProjectConfig : AbstractProjectConfig() {
    init {
        RootLogger.Logging.level = Level.DEBUG
    }
}

package io.github.fpaschos.pipekt.runtime.new

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runtime tuning knobs shared by the actor-backed pipeline runtime.
 */
data class RuntimeConfig(
    val workerPollInterval: Duration = 1.seconds,
    val watchdogInterval: Duration = 5.seconds,
    val leaseDuration: Duration = 30.seconds,
    val workerClaimLimit: Int = 32,
)

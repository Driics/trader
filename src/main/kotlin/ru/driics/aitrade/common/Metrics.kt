package ru.driics.aitrade.common

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

/**
 * Times a blocking operation and records the duration.
 * Returns Pair<Result, DurationMs>.
 * Uses nanoTime for duration measurements to avoid clock adjustments affecting measurements.
 */
inline fun <T> MeterRegistry.timed(
    metricName: String,
    vararg tags: String,
    crossinline block: () -> T
): Pair<T, Long> {
    val sample = Timer.start(this)
    var duration = 0L
    val startNanos = System.nanoTime()
    return try {
        val result = block()
        duration = (System.nanoTime() - startNanos) / 1_000_000 // Convert to milliseconds
        result to duration
    } finally {
        sample.stop(
            Timer.builder(metricName)
                .tags(*tags)
                .register(this)
        )
    }
}

/**
 * Times a suspend operation and records the duration.
 * Returns Pair<Result, DurationMs>.
 * Uses nanoTime for duration measurements to avoid clock adjustments affecting measurements.
 */
suspend inline fun <T> MeterRegistry.timedSuspend(
    metricName: String,
    vararg tags: String,
    crossinline block: suspend () -> T
): Pair<T, Long> {
    val sample = Timer.start(this)
    var duration = 0L

    return try {
        val startNanos = System.nanoTime()
        val result = block()
        duration = (System.nanoTime() - startNanos) / 1_000_000 // Convert to milliseconds
        result to duration
    } finally {
        sample.stop(
            Timer.builder(metricName)
                .tags(*tags)
                .register(this)
        )
    }
}

/**
 * Specialized timing for OKX HTTP operations with dynamic tags.
 * The tagsSupplier lambda is called after execution to capture dynamic status.
 */
suspend inline fun <T> MeterRegistry.timeOkx(
    operation: String,
    crossinline tagsSupplier: () -> Array<String>,
    crossinline block: suspend () -> T
): T {
    val sample = Timer.start(this)

    return try {
        block()
    } finally {
        val dynamicTags = tagsSupplier()
        sample.stop(
            Timer.builder("okx.http")
                .tag("operation", operation)
                .tags(*dynamicTags)
                .register(this)
        )
    }
}
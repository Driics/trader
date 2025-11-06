package ru.driics.aitrade.common

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

/**
 * Times a blocking operation and records the duration.
 * Returns Pair<Result, DurationMs>.
 */
inline fun <T> MeterRegistry.timed(
    metricName: String,
    vararg tags: String,
    crossinline block: () -> T
): Pair<T, Long> {
    var duration = 0L
    val result = Timer.builder(metricName)
        .tags(*tags)
        .register(this)
        .recordCallable {
            val startMs = System.currentTimeMillis()
            val r = block()
            duration = System.currentTimeMillis() - startMs
            r
        }!!
    return result to duration
}

/**
 * Times a suspend operation and records the duration.
 * Returns Pair<Result, DurationMs>.
 */
suspend inline fun <T> MeterRegistry.timedSuspend(
    metricName: String,
    vararg tags: String,
    crossinline block: suspend () -> T
): Pair<T, Long> {
    val sample = Timer.start(this)
    var duration = 0L

    return try {
        val startMs = System.currentTimeMillis()
        val result = block()
        duration = System.currentTimeMillis() - startMs
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
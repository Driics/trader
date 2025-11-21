package ru.driics.aitrade.common

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/**
 * Context object accessible inside the timed block.
 * Allows setting tags dynamically based on runtime logic.
 */
class TimerScope {
    private val tags = ArrayList<Tag>(4) // Low initial capacity to save memory
    var exceptionTag: String = "none"

    /**
     * Adds a dynamic tag to the timer.
     */
    fun tag(key: String, value: String) {
        tags.add(Tag.of(key, value))
    }

    /**
     * Helper to quickly set a status tag.
     */
    fun status(value: String) {
        tag("status", value)
    }

    /**
     * Internal use: returns collected tags as an Iterable.
     */
    fun getTags(): Iterable<Tag> = tags
}

/**
 * Standard blocking timing with dynamic tag support.
 */
inline fun <T> MeterRegistry.measure(
    metricName: String,
    vararg staticTags: String, // format: "key", "value", "key2", "value2"
    block: TimerScope.() -> T
): T {
    val sample = Timer.start(this)
    val scope = TimerScope()

    return try {
        val result = scope.block()
        result
    } catch (e: Throwable) {
        scope.exceptionTag = e::class.simpleName ?: "UnknownException"
        scope.status("error") // Auto-set status to error on exception
        throw e
    } finally {
        sample.stop(
            Timer.builder(metricName)
                .tags(*staticTags)
                .tags(scope.getTags())
                .tag("exception", scope.exceptionTag)
                .register(this)
        )
    }
}

/**
 * Suspending timing with dynamic tag support.
 *
 * Usage:
 * ```
 * return meterRegistry.measureSuspend("okx.trade", "side", "buy") {
 *     val response = api.call()
 *     if (response.code != 200) {
 *         status("http_${response.code}") // Dynamic tag!
 *     } else {
 *         status("ok")
 *     }
 *     response
 * }
 * ```
 */
suspend inline fun <T> MeterRegistry.measureSuspend(
    metricName: String,
    vararg staticTags: String,
    crossinline block: suspend TimerScope.() -> T
): T {
    val sample = Timer.start(this)
    val scope = TimerScope()

    return try {
        val result = scope.block()
        result
    } catch (e: Throwable) {
        scope.exceptionTag = e::class.simpleName ?: "UnknownException"
        scope.status("error")
        throw e
    } finally {
        sample.stop(
            Timer.builder(metricName)
                .tags(*staticTags)
                .tags(scope.getTags())
                .tag("exception", scope.exceptionTag)
                .register(this)
        )
    }
}

/**
 * Replacement for your specific `timeOkx`.
 * It hardcodes the metric name but allows dynamic tagging via the receiver.
 */
suspend inline fun <T> MeterRegistry.timeOkx(
    operation: String,
    extraTags: Array<String> = emptyArray(),
    crossinline block: suspend TimerScope.() -> T
): T {
    val sample = Timer.start(this)
    val scope = TimerScope()

    // Default status (can be overwritten inside block)
    scope.status("ok")

    return try {
        val result = scope.block()
        result
    } catch (e: Throwable) {
        scope.exceptionTag = e::class.simpleName ?: "UnknownException"
        // Note: 'status' tag is handled by the catch block or the user logic inside 'block'
        // if they want specific error mapping
        if (scope.exceptionTag != "none") {
            scope.status("exception")
        }
        throw e
    } finally {
        sample.stop(
            Timer.builder("okx.http")
                .tag("operation", operation)
                .tags(*extraTags)
                .tags(scope.getTags()) // Add the dynamic tags
                .tag("exception", scope.exceptionTag)
                .register(this)
        )
    }
}
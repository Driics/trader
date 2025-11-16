package ru.driics.aitrade.common.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import java.math.BigDecimal
import java.time.Clock

/**
 * Structured logger that outputs JSON-compatible log entries.
 * All logs include correlation ID and structured fields for easy parsing.
 */
class StructuredLogger(
    private val logger: KLogger,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Log an info event with structured data.
     */
    fun info(event: String, vararg fields: Pair<String, Any?>) {
        val context = buildStructuredContext(event, *fields)
        logger.info { formatStructuredMessage(event, context) }
    }

    /**
     * Log a warning event with structured data.
     */
    fun warn(event: String, vararg fields: Pair<String, Any?>) {
        val context = buildStructuredContext(event, *fields)
        logger.warn { formatStructuredMessage(event, context) }
    }

    /**
     * Log an error event with structured data.
     */
    fun error(event: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val context = buildStructuredContext(event, *fields)
        val message = formatStructuredMessage(event, context)
        if (throwable != null) {
            logger.error(throwable) { message }
        } else {
            logger.error { message }
        }
    }

    /**
     * Log a debug event with structured data.
     */
    fun debug(event: String, vararg fields: Pair<String, Any?>) {
        val context = buildStructuredContext(event, *fields)
        logger.debug { formatStructuredMessage(event, context) }
    }

    /**
     * Log a trace event with structured data.
     */
    fun trace(event: String, vararg fields: Pair<String, Any?>) {
        val context = buildStructuredContext(event, *fields)
        logger.trace { formatStructuredMessage(event, context) }
    }

    private fun buildStructuredContext(event: String, vararg fields: Pair<String, Any?>): Map<String, Any?> {
        val context = mutableMapOf<String, Any?>(
            "event" to event,
            "timestamp" to clock.instant().toEpochMilli()
        )

        // Add correlation ID if present
        CorrelationId.get()?.let {
            context["correlationId"] = it
        }

        // Add trace context if present
        MDC.get("traceId")?.let {
            context["traceId"] = it
        }
        MDC.get("spanId")?.let {
            context["spanId"] = it
        }

        // Add custom fields
        fields.forEach { (key, value) ->
            context[key] = serializeValue(value)
        }

        return context
    }

    private fun serializeValue(value: Any?): Any? {
        return when (value) {
            is BigDecimal -> value.toPlainString()
            is Number -> value
            is String -> value
            is Boolean -> value
            is Collection<*> -> value.map { serializeValue(it) }
            is Map<*, *> -> value.mapKeys { it.key.toString() }.mapValues { serializeValue(it.value) }
            null -> null
            else -> value.toString()
        }
    }

    private fun formatStructuredMessage(event: String, context: Map<String, Any?>): String {
        // Format as JSON-like string for logstash/logback-json encoder
        // The actual JSON encoding will be done by logback encoder
        val fields = context.entries.joinToString(", ") { (key, value) ->
            "$key=${formatValue(value)}"
        }
        return "[$event] $fields"
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            is String -> "\"$value\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            null -> "null"
            else -> "\"${value.toString()}\""
        }
    }
}

/**
 * Extension function to get a structured logger for a class.
 */
inline fun <reified T> structuredLogger(): StructuredLogger {
    return StructuredLogger(KotlinLogging.logger(T::class.java.name))
}

/**
 * Extension function to get a structured logger with a custom name.
 */
fun structuredLogger(name: String): StructuredLogger {
    return StructuredLogger(KotlinLogging.logger(name))
}


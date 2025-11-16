package ru.driics.aitrade.common.logging

import org.slf4j.MDC
import java.util.UUID

/**
 * Correlation ID management for request tracing.
 * Uses MDC (Mapped Diagnostic Context) for thread-local storage.
 */
object CorrelationId {
    private const val CORRELATION_ID_KEY = "correlationId"
    private const val TRACE_ID_KEY = "traceId"
    private const val SPAN_ID_KEY = "spanId"

    /**
     * Generate and set a new correlation ID for the current context.
     */
    fun generate(): String {
        val id = UUID.randomUUID().toString()
        set(id)
        return id
    }

    /**
     * Set a correlation ID for the current context.
     */
    fun set(correlationId: String) {
        MDC.put(CORRELATION_ID_KEY, correlationId)
    }

    /**
     * Get the current correlation ID.
     */
    fun get(): String? = MDC.get(CORRELATION_ID_KEY)

    /**
     * Clear the correlation ID from the current context.
     */
    fun clear() {
        MDC.remove(CORRELATION_ID_KEY)
        MDC.remove(TRACE_ID_KEY)
        MDC.remove(SPAN_ID_KEY)
    }

    /**
     * Execute a block with a correlation ID.
     * Automatically generates one if not present, and cleans up afterward.
     */
    inline fun <T> withCorrelationId(correlationId: String? = null, block: () -> T): T {
        val existing = get()
        val newId = correlationId ?: existing ?: generate()
        try {
            set(newId)
            return block()
        } finally {
            if (existing == null) {
                clear()
            } else {
                set(existing)
            }
        }
    }

    /**
     * Set trace and span IDs for OpenTelemetry integration.
     */
    fun setTraceContext(traceId: String, spanId: String) {
        MDC.put(TRACE_ID_KEY, traceId)
        MDC.put(SPAN_ID_KEY, spanId)
    }
}


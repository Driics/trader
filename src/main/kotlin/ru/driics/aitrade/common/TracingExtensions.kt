package ru.driics.aitrade.common

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext

/**
 * Extension for tracing suspend functions.
 */
suspend inline fun <T> Tracer.traced(
    spanName: String,
    crossinline attributes: SpanAttributesBuilder.() -> Unit = {},
    crossinline block: suspend (Span) -> T
): T {
    val span = spanBuilder(spanName)
        .setParent(Context.current())
        .startSpan()

    return try {
        withContext(Context.current().with(span).asContextElement()) {
            // Apply custom attributes
            val builder = SpanAttributesBuilder(span)
            builder.attributes()

            // Execute traced block
            val result = block(span)
            span.setStatus(StatusCode.OK)
            result
        }
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}

class SpanAttributesBuilder(private val span: Span) {
    fun attr(key: String, value: String) = span.setAttribute(key, value)
    fun attr(key: String, value: Long) = span.setAttribute(key, value)
    fun attr(key: String, value: Double) = span.setAttribute(key, value)
    fun attr(key: String, value: Boolean) = span.setAttribute(key, value)
}

/**
 * Add an event to the current span.
 */
fun Span.addEvent(name: String, vararg attributes: Pair<String, Any>) {
    val attrs = io.opentelemetry.api.common.Attributes.builder().apply {
        attributes.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Long -> put(key, value)
                is Int -> put(key, value.toLong())
                is Double -> put(key, value)
                is Boolean -> put(key, value)
            }
        }
    }.build()

    addEvent(name, attrs)
}
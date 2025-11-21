package ru.driics.aitrade.common.logging

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Servlet filter to extract and set correlation ID from HTTP headers.
 * Supports X-Correlation-ID, X-Request-ID, and X-Trace-ID headers.
 * If not present, generates a new correlation ID.
 */
@Component
@Order(1)
class CorrelationIdFilter : Filter {

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        try {
            // Try to get correlation ID from headers
            val correlationId = httpRequest.getHeader("X-Correlation-ID")
                ?: httpRequest.getHeader("X-Request-ID")
                ?: httpRequest.getHeader("X-Trace-ID")
                ?: CorrelationId.generate()

            // Set in MDC for logging
            CorrelationId.set(correlationId)

            // Add to response headers for client tracking
            httpResponse.setHeader("X-Correlation-ID", correlationId)

            chain.doFilter(request, response)
        } finally {
            // Clean up MDC after request
            CorrelationId.clear()
        }
    }
}


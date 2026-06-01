package ru.driics.aitrade.service.okx

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import ru.driics.aitrade.common.timeOkx
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.service.OkxAuthService

/**
 * Base class for OKX API clients providing common functionality.
 */
abstract class OkxClientBase(
    protected val okxProperties: OkxProperties,
    protected val okxKtorClient: HttpClient,
    protected val okxAuthService: OkxAuthService,
    protected val meterRegistry: MeterRegistry,
    protected val objectMapper: ObjectMapper,
    protected val tradingProperties: TradingProperties,
    protected val log: Logger
) {
    protected val baseUrl: String get() = okxProperties.baseUrl

    protected fun mapHttpStatus(statusCode: Int): String = when {
        statusCode in 400..499 -> "http_4xx"
        statusCode >= 500 -> "http_5xx"
        else -> "http_error"
    }

    protected fun recordHttpError(operation: String, statusCode: Int) {
        meterRegistry.counter(
            "okx.api.error",
            "operation", operation,
            "status", mapHttpStatus(statusCode)
        ).increment()
    }
}


package ru.driics.aitrade.service.okx

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import io.github.resilience4j.retry.annotation.Retry
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.driics.aitrade.common.timeOkx
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.service.OkxAuthService
import java.math.BigDecimal

/**
 * Client for OKX market data operations (ticker, candles, funding rate, open interest, instruments).
 */
@Component
class OkxMarketDataClient(
    okxProperties: OkxProperties,
    okxKtorClient: HttpClient,
    okxAuthService: OkxAuthService,
    meterRegistry: MeterRegistry,
    objectMapper: ObjectMapper,
    tradingProperties: TradingProperties
) : OkxClientBase(
    okxProperties, okxKtorClient, okxAuthService, meterRegistry, objectMapper, tradingProperties,
    LoggerFactory.getLogger(OkxMarketDataClient::class.java)
) {

    private companion object {
        const val METRIC_NAME = "okxMarket"
        const val PATH_TICKER = "/api/v5/market/ticker"
        const val PATH_CANDLES = "/api/v5/market/candles"
        const val PATH_FUNDING = "/api/v5/public/funding-rate"
        const val PATH_OPEN_INTEREST = "/api/v5/public/open-interest"
        const val PATH_INSTRUMENTS = "/api/v5/public/instruments"
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME, fallbackMethod = "fetchTickerFallback")
    suspend fun fetchTicker(instId: String): OkxTickerResponse? {
        return executePublicRequest(
            operation = "fetchTicker",
            url = "$baseUrl$PATH_TICKER?instId=$instId",
            timeoutMs = tradingProperties.okxTimeouts.ticker.toMillis(),
            defaultResult = null
        ) { body ->
            val response = objectMapper.readValue<OkxApiResponse<OkxTickerResponse>>(body)
            if (response.isSuccess()) {
                response.getFirstOrNull() ?: run {
                    log.warn("No ticker data found for $instId")
                    null
                }
            } else {
                logApiError(instId, "ticker", response)
                null
            }
        }
    }

    private fun fetchTickerFallback(instId: String, ex: Exception): OkxTickerResponse? {
        log.warn("Circuit breaker fallback for fetchTicker($instId)", ex)
        return null
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME, fallbackMethod = "fetchCandlesFallback")
    suspend fun fetchCandles(instId: String, period: String, limit: Int): List<OkxCandleResponse> {
        return executePublicRequest(
            operation = "fetchCandles",
            url = "$baseUrl$PATH_CANDLES?instId=$instId&bar=$period&limit=$limit",
            timeoutMs = tradingProperties.okxTimeouts.candles.toMillis(),
            defaultResult = emptyList(),
            extraMetricTags = arrayOf("period", period)
        ) { body ->
            val response = objectMapper.readValue<OkxCandlesApiResponse>(body)
            if (response.isSuccess()) {
                response.toCandles().sortedBy { it.timestamp.toLongOrNull() ?: Long.MAX_VALUE }
            } else {
                //FIXME: logApiError(instId, "candles", response)
                emptyList()
            }
        }
    }

    private fun fetchCandlesFallback(instId: String, period: String, limit: Int, ex: Exception): List<OkxCandleResponse> {
        log.warn("Circuit breaker fallback for fetchCandles($instId, $period)", ex)
        return emptyList()
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun fetchFundingRate(instId: String): BigDecimal? {
        return executePublicRequest(
            operation = "fetchFundingRate",
            url = "$baseUrl$PATH_FUNDING?instId=$instId",
            timeoutMs = tradingProperties.okxTimeouts.funding.toMillis(),
            defaultResult = null
        ) { body ->
            val response = objectMapper.readValue<OkxApiResponse<OkxFundingResponse>>(body)
            if (response.isSuccess()) {
                response.getFirstOrNull()?.fundingRate?.toBigDecimalOrNull()
            } else {
                logApiError(instId, "funding rate", response)
                null
            }
        }
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun fetchOpenInterest(instId: String): BigDecimal? {
        return executePublicRequest(
            operation = "fetchOpenInterest",
            url = "$baseUrl$PATH_OPEN_INTEREST?instId=$instId",
            timeoutMs = tradingProperties.okxTimeouts.openInterest.toMillis(),
            defaultResult = null
        ) { body ->
            val response = objectMapper.readValue<OkxApiResponse<OkxOpenInterestResponse>>(body)
            if (response.isSuccess()) {
                response.getFirstOrNull()?.openInterest?.toBigDecimalOrNull()
            } else {
                logApiError(instId, "open interest", response)
                null
            }
        }
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    suspend fun getSwapInstrument(instId: String): OkxInstrumentInfo? {
        return executePublicRequest(
            operation = "getSwapInstrument",
            url = "$baseUrl$PATH_INSTRUMENTS?instType=SWAP&instId=$instId",
            timeoutMs = tradingProperties.okxTimeouts.instruments.toMillis(),
            defaultResult = null
        ) { body ->
            val response = objectMapper.readValue<OkxPublicInstrumentsApiResponse>(body)
            if (response.isSuccess()) {
                response.firstOrNull()
            } else {
                //FIXME: logApiError(instId, "instruments", response)
                null
            }
        }
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Generalized executor for Public GET requests.
     * Handles:
     * 1. Timeouts
     * 2. Metrics (Duration + Dynamic Status Tags)
     * 3. HTTP Error Mapping
     * 4. Exception Logging
     */
    private suspend inline fun <T> executePublicRequest(
        operation: String,
        url: String,
        timeoutMs: Long,
        defaultResult: T,
        extraMetricTags: Array<String> = emptyArray(),
        crossinline block: (String) -> T
    ): T {
        // Use the TimerScope-based extension from previous refactoring
        return meterRegistry.timeOkx(operation, extraMetricTags) {
            try {
                withTimeout(timeoutMs) {
                    val response = okxKtorClient.get(url)

                    // 1. Handle HTTP Errors
                    if (!response.status.isSuccess()) {
                        val code = response.status.value
                        status(mapHttpStatus(code)) // Dynamic Tag
                        log.warn("HTTP $code for $operation: $url")
                        return@withTimeout defaultResult
                    }

                    // 2. Process Body
                    val body = response.bodyAsText()
                    block(body)
                }
            } catch (e: TimeoutCancellationException) {
                status("timeout") // Dynamic Tag
                // Timeouts are expected in high-frequency trading, debug level is often enough
                log.debug("Timeout fetching $operation")
                defaultResult
            } catch (e: ClientRequestException) {
                status(mapHttpStatus(e.response.status.value)) // Dynamic Tag
                log.error("HTTP Client error fetching $operation", e)
                defaultResult
            } catch (e: Exception) {
                status("error") // Dynamic Tag
                log.error("Unexpected error fetching $operation", e)
                defaultResult
            }
        }
    }

    private fun logApiError(instId: String, type: String, response: OkxApiResponse<*>) {
        log.warn("OKX API error for $type $instId - Code: ${response.code}, Message: ${response.message}" )
    }

    private fun mapHttpStatus(code: Int): String = when {
        code in 400..499 -> "http_4xx"
        code >= 500 -> "http_5xx"
        else -> "http_error"
    }
}
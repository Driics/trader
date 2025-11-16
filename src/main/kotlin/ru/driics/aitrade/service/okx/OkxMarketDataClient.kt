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
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.driics.aitrade.common.timeOkx
import ru.driics.aitrade.common.withTimeoutAndLog
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

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    @CircuitBreaker(name = "okxMarket", fallbackMethod = "fetchTickerFallback")
    suspend fun fetchTicker(instId: String): OkxTickerResponse? {
        var status = "success"

        return meterRegistry.timeOkx("fetchTicker", { arrayOf("status", status) }) {
            try {
                withTimeoutAndLog(tradingProperties.okxTimeouts.ticker.toMillis(), null, "fetchTicker($instId)") {
                    val url = "$baseUrl/api/v5/market/ticker?instId=$instId"
                    val response: HttpResponse = okxKtorClient.get(url)
                    val body = response.bodyAsText()
                    val apiResponse = objectMapper.readValue<OkxApiResponse<OkxTickerResponse>>(body)

                    if (!apiResponse.isSuccess()) {
                        status = "api_error_${apiResponse.code}"
                        log.warn("OKX API error for ticker $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                        return@withTimeoutAndLog null
                    }

                    apiResponse.getFirstOrNull() ?: run {
                        status = "empty_data"
                        log.warn("No ticker data found for $instId")
                        null
                    }
                }
            } catch (e: TimeoutCancellationException) {
                status = "timeout"
                null
            } catch (e: ClientRequestException) {
                status = "http_${e.response.status.value}"
                log.error("HTTP error fetching ticker for $instId: ${e.response.status}", e)
                null
            } catch (e: Exception) {
                status = "error"
                log.error("Error fetching ticker for $instId", e)
                null
            }
        }
    }

    private fun fetchTickerFallback(instId: String, ex: Exception): OkxTickerResponse? {
        log.warn("Circuit breaker fallback for fetchTicker($instId)", ex)
        return null
    }

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    @CircuitBreaker(name = "okxMarket", fallbackMethod = "fetchCandlesFallback")
    suspend fun fetchCandles(instId: String, period: String, limit: Int): List<OkxCandleResponse> {
        var status = "success"

        return meterRegistry.timeOkx("fetchCandles", { arrayOf("period", period, "status", status) }) {
            try {
                withTimeout(tradingProperties.okxTimeouts.candles.toMillis()) {
                    val url = "$baseUrl/api/v5/market/candles?instId=$instId&bar=$period&limit=$limit"
                    val response: HttpResponse = okxKtorClient.get(url)
                    val body = response.bodyAsText()
                    val apiResponse = objectMapper.readValue<OkxCandlesApiResponse>(body)

                    if (!apiResponse.isSuccess()) {
                        status = "api_error_${apiResponse.code}"
                        log.warn("OKX API error for candles $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                        return@withTimeout emptyList()
                    }

                    apiResponse.toCandles().sortedBy { it.timestamp.toLongOrNull() ?: Long.MAX_VALUE }
                }
            } catch (e: TimeoutCancellationException) {
                status = "timeout"; emptyList()
            } catch (e: ClientRequestException) {
                status = "http_${e.response.status.value}"
                log.error("HTTP error fetching candles for $instId, period: $period: ${e.response.status}", e)
                emptyList()
            } catch (e: Exception) {
                status = "error"
                log.error("Error fetching candles for $instId, period: $period", e)
                emptyList()
            }
        }
    }

    private fun fetchCandlesFallback(instId: String, period: String, limit: Int, ex: Exception): List<OkxCandleResponse> {
        log.warn("Circuit breaker fallback for fetchCandles($instId, $period)", ex)
        return emptyList()
    }

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    @CircuitBreaker(name = "okxMarket")
    suspend fun fetchFundingRate(instId: String): BigDecimal? {
        var status = "success"

        return meterRegistry.timeOkx("fetchFundingRate", { arrayOf("status", status) }) {
            try {
                withTimeout(tradingProperties.okxTimeouts.funding.toMillis()) {
                    val url = "$baseUrl/api/v5/public/funding-rate?instId=$instId"
                    val response: HttpResponse = okxKtorClient.get(url)
                    val body = response.bodyAsText()
                    val apiResponse = objectMapper.readValue<OkxApiResponse<OkxFundingResponse>>(body)

                    if (!apiResponse.isSuccess()) {
                        status = "api_error_${apiResponse.code}"
                        log.warn("OKX API error for funding rate $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                        return@withTimeout null
                    }

                    apiResponse.getFirstOrNull()?.fundingRate?.toBigDecimalOrNull() ?: run {
                        status = "empty_data"
                        log.debug("No funding rate data for $instId")
                        null
                    }
                }
            } catch (e: Exception) {
                status = "error"
                log.error("Error fetching funding rate for $instId", e)
                null
            }
        }
    }

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    @CircuitBreaker(name = "okxMarket")
    suspend fun fetchOpenInterest(instId: String): BigDecimal? {
        var status = "success"

        return meterRegistry.timeOkx("fetchOpenInterest", { arrayOf("status", status) }) {
            try {
                withTimeout(tradingProperties.okxTimeouts.funding.toMillis()) {
                    val url = "$baseUrl/api/v5/public/open-interest?instId=$instId"
                    val response: HttpResponse = okxKtorClient.get(url)
                    val body = response.bodyAsText()
                    val apiResponse = objectMapper.readValue<OkxApiResponse<OkxOpenInterestResponse>>(body)

                    if (!apiResponse.isSuccess()) {
                        status = "api_error_${apiResponse.code}"
                        log.warn("OKX API error for open interest $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                        return@withTimeout null
                    }

                    apiResponse.getFirstOrNull()?.openInterest?.toBigDecimalOrNull() ?: run {
                        status = "empty_data"
                        log.debug("No open interest data for $instId")
                        null
                    }
                }
            } catch (e: Exception) {
                status = "error"
                log.error("Error fetching open interest for $instId", e)
                null
            }
        }
    }

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    suspend fun getSwapInstrument(instId: String): OkxInstrumentInfo? {
        var status = "success"

        return meterRegistry.timeOkx("getSwapInstrument", { arrayOf("status", status) }) {
            try {
                withTimeout(tradingProperties.okxTimeouts.instruments.toMillis()) {
                    val url = "$baseUrl/api/v5/public/instruments?instType=SWAP&instId=$instId"
                    val response: HttpResponse = okxKtorClient.get(url)
                    val body = response.bodyAsText()
                    val apiResponse = objectMapper.readValue<OkxPublicInstrumentsApiResponse>(body)

                    if (!apiResponse.isSuccess()) {
                        status = "api_error"
                        log.warn("Instruments fetch failed for $instId - code=${apiResponse.code}, msg=${apiResponse.msg}")
                        null
                    } else {
                        apiResponse.firstOrNull()
                    }
                }
            } catch (e: Exception) {
                status = "error"
                log.error("Error getting instrument {}", instId, e)
                null
            }
        }
    }
}


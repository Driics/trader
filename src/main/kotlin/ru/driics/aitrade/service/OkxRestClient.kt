package ru.driics.aitrade.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import io.github.resilience4j.retry.annotation.Retry
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.OkxHttpProperties
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.model.*
import java.math.BigDecimal

@Service
class OkxRestClient(
    private val okxProperties: OkxProperties,
    private val okxHttpProps: OkxHttpProperties,
    private val okxKtorClient: HttpClient,
    private val okxAuthService: OkxAuthService,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    @CircuitBreaker(name = "okxMarket", fallbackMethod = "fetchTickerFallback")
    suspend fun fetchTicker(instId: String): OkxTickerResponse? {
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/ticker?instId=$instId"

            val response: HttpResponse = okxKtorClient.get(url)
            val body = response.bodyAsText()

            val apiResponse = objectMapper.readValue<OkxApiResponse<OkxTickerResponse>>(body)

            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for ticker $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return null
            }

            apiResponse.getFirstOrNull() ?: run {
                status = "empty_data"
                log.warn("No ticker data found for $instId")
                null
            }
        } catch (e: ClientRequestException) {
            status = "http_${e.response.status.value}"
            log.error("HTTP error fetching ticker for $instId: ${e.response.status}", e)
            null
        } catch (e: Exception) {
            status = "error"
            log.error("Error fetching ticker for $instId", e)
            null
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "fetchTicker")
                    .tag("status", status)
                    .register(meterRegistry)
            )
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
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/candles?instId=$instId&bar=$period&limit=$limit"

            val response: HttpResponse = okxKtorClient.get(url)
            val body = response.bodyAsText()

            val apiResponse = objectMapper.readValue<OkxCandlesApiResponse>(body)

            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for candles $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return emptyList()
            }

            apiResponse.toCandles().sortedBy { it.timestamp.toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: ClientRequestException) {
            status = "http_${e.response.status.value}"
            log.error("HTTP error fetching candles for $instId, period: $period: ${e.response.status}", e)
            emptyList()
        } catch (e: Exception) {
            status = "error"
            log.error("Error fetching candles for $instId, period: $period", e)
            emptyList()
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "fetchCandles")
                    .tag("period", period)
                    .tag("status", status)
                    .register(meterRegistry)
            )
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
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/funding-rate?instId=$instId"

            val response: HttpResponse = okxKtorClient.get(url)
            val body = response.bodyAsText()

            val apiResponse = objectMapper.readValue<OkxApiResponse<OkxFundingResponse>>(body)

            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for funding rate $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return null
            }

            apiResponse.getFirstOrNull()?.fundingRate?.toBigDecimalOrNull() ?: run {
                status = "empty_data"
                log.debug("No funding rate data for $instId")
                null
            }
        } catch (e: Exception) {
            status = "error"
            log.error("Error fetching funding rate for $instId", e)
            null
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "fetchFundingRate")
                    .tag("status", status)
                    .register(meterRegistry)
            )
        }
    }

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    @CircuitBreaker(name = "okxMarket")
    suspend fun fetchOpenInterest(instId: String): BigDecimal? {
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/open-interest?instId=$instId"

            val response: HttpResponse = okxKtorClient.get(url)
            val body = response.bodyAsText()

            val apiResponse = objectMapper.readValue<OkxApiResponse<OkxOpenInterestResponse>>(body)

            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for open interest $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return null
            }

            apiResponse.getFirstOrNull()?.openInterest?.toBigDecimalOrNull() ?: run {
                status = "empty_data"
                log.debug("No open interest data for $instId")
                null
            }
        } catch (e: Exception) {
            status = "error"
            log.error("Error fetching open interest for $instId", e)
            null
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "fetchOpenInterest")
                    .tag("status", status)
                    .register(meterRegistry)
            )
        }
    }

    // Account methods - different policy (fewer retries, longer timeouts)

    @Retry(name = "okxAccount")
    @RateLimiter(name = "okxAccount")
    @CircuitBreaker(name = "okxAccount")
    suspend fun fetchAccount(): OkxAccountResponse {
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val path = "/api/v5/account/balance"
            val url = "${okxProperties.baseUrl}$path"

            val authHeaders = okxAuthService.createAuthHeaders("GET", path)
            val response: HttpResponse = okxKtorClient.get(url) {
                authHeaders.forEach { (key, value) -> header(key, value) }
            }

            val body = response.bodyAsText()
            val apiResponse = objectMapper.readValue<OkxAccountApiResponse>(body)

            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for account - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return OkxAccountResponse("0", "0", "0", "0")
            }

            val data = apiResponse.getFirstOrNull()
                ?: return OkxAccountResponse("0", "0", "0", "0")

            val totalEq = data.totalEquity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val availEqUsd = data.availableEquityUsd.toBigDecimalOrNull() ?: BigDecimal.ZERO

            // Try per‑ccy USDT as fallback for available cash if availEq isn’t available
            val usdtAvailBal = data.details.firstOrNull { it.currency.equals("USDT", ignoreCase = true) }
                ?.availableBalance?.toBigDecimalOrNull()

            val derivedAvailable = when {
                availEqUsd > BigDecimal.ZERO -> availEqUsd
                usdtAvailBal != null -> usdtAvailBal
                else -> BigDecimal.ZERO
            }

            OkxAccountResponse(
                totalEquity = totalEq.toPlainString(),
                availableBalance = derivedAvailable.toPlainString(),
                cashBalance = data.cashBalance, // kept as-is (per-ccy)
                unrealizedPnl = data.unrealizedPnl
            )
        } catch (e: Exception) {
            status = "error"
            log.error("Error fetching account info", e)
            OkxAccountResponse("0", "0", "0", "0")
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "fetchAccount")
                    .tag("status", status)
                    .register(meterRegistry)
            )
        }
    }

    @Retry(name = "okxAccount")
    @RateLimiter(name = "okxAccount")
    @CircuitBreaker(name = "okxAccount")
    suspend fun fetchOpenPositions(): List<OkxPositionResponse> {
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val path = "/api/v5/account/positions?instType=SWAP"
            val url = "${okxProperties.baseUrl}$path"

            val authHeaders = okxAuthService.createAuthHeaders("GET", path)

            val response: HttpResponse = okxKtorClient.get(url) {
                authHeaders.forEach { (key, value) -> header(key, value) }
            }
            val body = response.bodyAsText()
            val apiResponse = objectMapper.readValue<OkxApiResponse<OkxPositionResponse>>(body)

            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for positions - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return emptyList()
            }

            apiResponse.data.ifEmpty {
                log.debug("No open positions found")
            }

            apiResponse.data
        } catch (e: Exception) {
            status = "error"
            log.error("Error fetching open positions", e)
            emptyList()
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "fetchOpenPositions")
                    .tag("status", status)
                    .register(meterRegistry)
            )
        }
    }

    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    suspend fun getSwapInstrument(instId: String): OkxInstrumentInfo? {
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/instruments?instType=SWAP&instId=$instId"
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
        } catch (e: Exception) {
            status = "error"
            log.error("Error getting instrument {}", instId, e)
            null
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "getSwapInstrument")
                    .tag("status", status)
                    .register(meterRegistry)
            )
        }
    }

    // Trading methods - critical path with strictest policies

    @Retry(name = "okxTrade")
    @RateLimiter(name = "okxTrade")
    @CircuitBreaker(name = "okxTrade")
    suspend fun setLeverage(instId: String, leverage: Int, marginMode: MarginMode, posSide: String? = null): Boolean {
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            val path = "/api/v5/account/set-leverage"
            val url = "${okxProperties.baseUrl}$path"
            val payload = mutableMapOf(
                "instId" to instId,
                "lever" to leverage.toString(),
                "mgnMode" to marginMode.asOkxApiValue
            )
            posSide?.let { payload["posSide"] = it }
            val bodyJson = objectMapper.writeValueAsString(payload)
            val authHeaders = okxAuthService.createAuthHeaders("POST", path, bodyJson)

            val response: HttpResponse = okxKtorClient.post(url) {
                authHeaders.forEach { (key, value) -> header(key, value) }
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }

            val responseBody = response.bodyAsText()
            val responseMap = objectMapper.readValue<Map<String, Any>>(responseBody)
            val ok = (responseMap["code"]?.toString() == "0")

            if (!ok) {
                status = "failed"
                log.warn("Set leverage failed for $instId: body=$responseBody")
            }
            ok
        } catch (e: Exception) {
            status = "error"
            log.error("Error setting leverage for $instId", e)
            false
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "setLeverage")
                    .tag("status", status)
                    .register(meterRegistry)
            )
        }
    }

    @Retry(name = "okxTrade")
    @RateLimiter(name = "okxTrade")
    @CircuitBreaker(name = "okxTrade")
    suspend fun placeMarketOrderWithAttach(
        instId: String,
        side: String,             // "buy" | "sell"
        tdMode: String = "isolated", // "cross" | "isolated"
        szContracts: String,      // size in contracts (respect lotSz and minSz)
        tpPx: String? = null,     // take-profit price (optional)
        slPx: String? = null,     // stop-loss price (optional)
        posSide: String? = null,  // optional for long/short mode
        clOrdId: String? = null,
        tag: String? = "ai-signal"
    ): OkxPlaceOrderData? {
        val sample = Timer.start(meterRegistry)
        var status = "success"

        return try {
            clOrdId?.let { MDC.put("clOrdId", it) }
            MDC.put("instId", instId)
            MDC.put("side", side)

            val path = "/api/v5/trade/order"
            val url = "${okxProperties.baseUrl}$path"

            val payload = mutableMapOf<String, Any>(
                "instId" to instId,
                "tdMode" to tdMode,
                "side" to side,
                "ordType" to "market",
                "sz" to szContracts
            )
            clOrdId?.let { payload["clOrdId"] = it }
            tag?.let { payload["tag"] = it }
            posSide?.let { payload["posSide"] = it }

            // Attach TP/SL using new attachAlgoOrds array (OKX docs)
            val attach = mutableListOf<MutableMap<String, String>>()
            if (!tpPx.isNullOrBlank()) {
                attach += mutableMapOf(
                    "tpTriggerPx" to tpPx,
                    "tpOrdPx" to tpPx,      // limit TP at the same price; alternatively set tpOrdKind="limit"
                    "tpOrdKind" to "limit"
                )
            }
            if (!slPx.isNullOrBlank()) {
                attach += mutableMapOf(
                    "slTriggerPx" to slPx,
                    "slOrdPx" to "-1"       // market stop-loss on trigger
                )
            }
            if (attach.isNotEmpty()) payload["attachAlgoOrds"] = attach

            val bodyJson = objectMapper.writeValueAsString(payload)
            val authHeaders = okxAuthService.createAuthHeaders("POST", path, bodyJson)

            val response: HttpResponse = okxKtorClient.post(url) {
                authHeaders.forEach { (key, value) -> header(key, value) }
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }

            val responseBody = response.bodyAsText()
            val apiResponse = objectMapper.readValue<OkxPlaceOrderApiResponse>(responseBody)

            if (!apiResponse.isSuccess()) {
                status = "rejected"
                log.warn("Order rejected $clOrdId: code=${apiResponse.code}, msg=${apiResponse.msg}, data=${apiResponse.data}")
            }

            if (!apiResponse.isSuccess()) {
                log.warn("Order rejected $clOrdId: code=${apiResponse.code}, msg=${apiResponse.msg}, data=${apiResponse.data}")
            }
            apiResponse.firstOrNull()
        } catch (e: Exception) {
            status = "error"
            log.error("Error placing order $clOrdId", e)
            null
        } finally {
            sample.stop(
                Timer.builder("okx.http")
                    .tag("operation", "placeOrder")
                    .tag("side", side)
                    .tag("status", status)
                    .register(meterRegistry)
            )
            MDC.clear()
        }
    }
}
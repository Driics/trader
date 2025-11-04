package ru.driics.aitrade.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import io.github.resilience4j.retry.annotation.Retry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import ru.driics.aitrade.config.OkxHttpProperties
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.model.*
import java.math.BigDecimal

@Service
class OkxRestClient(
    private val okxProperties: OkxProperties,
    private val okxHttpProps: OkxHttpProperties,
    private val okxWebClient: WebClient,
    private val okxAuthService: OkxAuthService,
    private val meterRegistry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    // Market data methods - with retry, rate limiting, and circuit breaker
    
    @Retry(name = "okxMarket")
    @RateLimiter(name = "okxMarket")
    @CircuitBreaker(name = "okxMarket", fallbackMethod = "fetchTickerFallback")
    suspend fun fetchTicker(instId: String): OkxTickerResponse? {
        val sample = Timer.start(meterRegistry)
        var status = "success"
        
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/ticker?instId=$instId"
            
            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxTickerResponse>>() {}
            val apiResponse = okxWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (apiResponse == null) {
                status = "null_response"
                log.warn("Received null response for ticker: $instId")
                return null
            }
            
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
        } catch (e: WebClientResponseException) {
            status = "http_${e.statusCode.value()}"
            log.error("HTTP error fetching ticker for $instId: ${e.statusCode}", e)
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
            
            val responseType = object : ParameterizedTypeReference<OkxCandlesApiResponse>() {}
            val apiResponse = okxWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (apiResponse == null) {
                status = "null_response"
                log.warn("Received null response for candles: $instId, period: $period")
                return emptyList()
            }
            
            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for candles $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return emptyList()
            }
            
            apiResponse.toCandles().sortedBy { it.timestamp.toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: WebClientResponseException) {
            status = "http_${e.statusCode.value()}"
            log.error("HTTP error fetching candles for $instId, period: $period: ${e.statusCode}", e)
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
            
            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxFundingResponse>>() {}
            val apiResponse = okxWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (apiResponse == null) {
                status = "null_response"
                log.warn("Received null response for funding rate: $instId")
                return null
            }
            
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
            
            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxOpenInterestResponse>>() {}
            val apiResponse = okxWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (apiResponse == null) {
                status = "null_response"
                log.warn("Received null response for open interest: $instId")
                return null
            }
            
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
            val url = "${okxProperties.baseUrl}/api/v5/account/balance"
            val requestPath = "/api/v5/account/balance"
            
            val authHeaders = okxAuthService.createAuthHeaders("GET", requestPath)
            
            val responseType = object : ParameterizedTypeReference<OkxAccountApiResponse>() {}
            val apiResponse = okxWebClient.get()
                .uri(url)
                .headers { headers ->
                    authHeaders.forEach { (key, value) -> headers.set(key, value) }
                }
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (apiResponse == null) {
                status = "null_response"
                log.warn("Received null response body for account")
                return OkxAccountResponse("0", "0", "0", "0")
            }
            
            if (!apiResponse.isSuccess()) {
                status = "api_error_${apiResponse.code}"
                log.warn("OKX API error for account - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return OkxAccountResponse("0", "0", "0", "0")
            }
            
            val data = apiResponse.getFirstOrNull()
                ?: return OkxAccountResponse("0", "0", "0", "0")
            
            val totalEq = data.totalEquity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val availEqUsd = data.availableEquityUsd.toBigDecimalOrNull() ?: BigDecimal.ZERO
            
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
                cashBalance = data.cashBalance,
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
            val url = "${okxProperties.baseUrl}/api/v5/account/positions?instType=SWAP"
            val requestPath = "/api/v5/account/positions?instType=SWAP"
            
            val authHeaders = okxAuthService.createAuthHeaders("GET", requestPath)
            
            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxPositionResponse>>() {}
            val apiResponse = okxWebClient.get()
                .uri(url)
                .headers { headers ->
                    authHeaders.forEach { (key, value) -> headers.set(key, value) }
                }
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (apiResponse == null) {
                status = "null_response"
                log.warn("Received null response body for positions")
                return emptyList()
            }
            
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
            
            val responseType = object : ParameterizedTypeReference<OkxPublicInstrumentsApiResponse>() {}
            val body = okxWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (body == null || !body.isSuccess()) {
                status = "api_error"
                log.warn("Instruments fetch failed for $instId - code=${body?.code}, msg=${body?.msg}")
                null
            } else {
                body.firstOrNull()
            }
        } catch (e: Exception) {
            status = "error"
            log.error("Error getting instrument $instId", e)
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
            
            val responseType = object : ParameterizedTypeReference<Map<String, Any>>() {}
            val resp = okxWebClient.post()
                .uri(url)
                .headers { headers ->
                    authHeaders.forEach { (key, value) -> headers.set(key, value) }
                }
                .bodyValue(bodyJson)
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            val ok = (resp?.get("code")?.toString() == "0")
            if (!ok) {
                status = "failed"
                log.warn("Set leverage failed for $instId: body=$resp")
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
        side: String,
        tdMode: String = "cross",
        szContracts: String,
        tpPx: String? = null,
        slPx: String? = null,
        posSide: String? = null,
        clOrdId: String? = null,
        tag: String? = "ai-signal"
    ): OkxPlaceOrderData? {
        val sample = Timer.start(meterRegistry)
        var status = "success"
        
        // Add MDC context for structured logging
        try {
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
            
            val attach = mutableListOf<MutableMap<String, String>>()
            if (!tpPx.isNullOrBlank()) {
                attach += mutableMapOf(
                    "tpTriggerPx" to tpPx,
                    "tpOrdPx" to tpPx,
                    "tpOrdKind" to "limit"
                )
            }
            if (!slPx.isNullOrBlank()) {
                attach += mutableMapOf(
                    "slTriggerPx" to slPx,
                    "slOrdPx" to "-1"
                )
            }
            if (attach.isNotEmpty()) payload["attachAlgoOrds"] = attach
            
            val bodyJson = objectMapper.writeValueAsString(payload)
            val authHeaders = okxAuthService.createAuthHeaders("POST", path, bodyJson)
            
            val responseType = object : ParameterizedTypeReference<OkxPlaceOrderApiResponse>() {}
            val api = okxWebClient.post()
                .uri(url)
                .headers { headers ->
                    authHeaders.forEach { (key, value) -> headers.set(key, value) }
                }
                .bodyValue(bodyJson)
                .retrieve()
                .bodyToMono(responseType)
                .awaitSingleOrNull()
            
            if (api == null) {
                status = "null_response"
                log.warn("Null response placing order $clOrdId")
                return null
            }
            if (!api.isSuccess()) {
                status = "rejected"
                log.warn("Order rejected $clOrdId: code=${api.code}, msg=${api.msg}, data=${api.data}")
            }
            api.firstOrNull()
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
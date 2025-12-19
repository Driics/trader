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
import io.ktor.http.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.common.timeOkx
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.domain.model.OkxPlaceOrderApiResponse
import ru.driics.aitrade.domain.model.OkxPlaceOrderData
import ru.driics.aitrade.domain.services.TradingMetricsService
import ru.driics.aitrade.service.OkxAuthService

/**
 * Client for OKX trading operations (set leverage, place orders).
 */
@Component
class OkxTradingClient(
    okxProperties: OkxProperties,
    okxKtorClient: HttpClient,
    okxAuthService: OkxAuthService,
    meterRegistry: MeterRegistry,
    objectMapper: ObjectMapper,
    tradingProperties: TradingProperties,
    private val tradingMetricsService: TradingMetricsService
) : OkxClientBase(
    okxProperties, okxKtorClient, okxAuthService, meterRegistry, objectMapper, tradingProperties,
    LoggerFactory.getLogger(OkxTradingClient::class.java)
) {

    private companion object {
        const val METRIC_NAME = "okxTrade"
        const val PATH_SET_LEVERAGE = "/api/v5/account/set-leverage"
        const val PATH_PLACE_ORDER = "/api/v5/trade/order"
        const val TAG_AI_SIGNAL = "ai-signal"
        const val ORD_TYPE_MARKET = "market"
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun setLeverage(
        instId: String,
        leverage: Int,
        marginMode: MarginMode,
        posSide: String? = null
    ): Boolean {
        val payload = buildMap {
            put("instId", instId)
            put("lever", leverage.toString())
            put("mgnMode", marginMode.asOkxApiValue)
            if (posSide != null) put("posSide", posSide)
        }

        return executeOkxCall(
            operationName = "setLeverage",
            path = PATH_SET_LEVERAGE,
            payload = payload,
            timeoutMs = tradingProperties.okxTimeouts.setLeverage.toMillis(),
            contextTags = mapOf("instId" to instId)
        ) { responseBody ->
            val responseMap = objectMapper.readValue<Map<String, Any>>(responseBody)
            val isSuccess = responseMap["code"]?.toString() == "0"

            if (!isSuccess) {
                log.warn("Set leverage failed for $instId: $responseBody")
            }
            isSuccess
        } ?: false
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun placeMarketOrderWithAttach(
        instId: String,
        side: String,
        tdMode: String = "isolated",
        szContracts: String,
        tpPx: String? = null,
        slPx: String? = null,
        posSide: String? = null,
        clOrdId: String? = null,
        tag: String? = TAG_AI_SIGNAL
    ): OkxPlaceOrderData? {
        val payload = buildOrderPayload(instId, side, tdMode, szContracts, tpPx, slPx, posSide, clOrdId, tag)

        val mdcTags = buildMap {
            put("instId", instId)
            put("side", side)
            if (clOrdId != null) put("clOrdId", clOrdId)
        }

        return executeOkxCall(
            operationName = "placeOrder",
            path = PATH_PLACE_ORDER,
            payload = payload,
            timeoutMs = tradingProperties.okxTimeouts.placeOrder.toMillis(),
            contextTags = mdcTags,
            extraMetricTags = arrayOf("side", side)
        ) { responseBody ->
            val apiResponse = objectMapper.readValue<OkxPlaceOrderApiResponse>(responseBody)

            if (!apiResponse.isSuccess()) {
                handleOrderFailure(apiResponse, instId, clOrdId)
                // Record metric failure
                tradingMetricsService.recordOrderRejected(
                    symbol = instId.substringBefore("-"),
                    reason = apiResponse.msg ?: "API Error ${apiResponse.code}"
                )
                null
            } else {
                // Record metric success
                tradingMetricsService.recordOrderPlaced(
                    symbol = instId.substringBefore("-"),
                    side = side,
                    type = ORD_TYPE_MARKET
                )
                apiResponse.firstOrNull()
            }
        }
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Centralized execution wrapper handling:
     * 1. Serialization & Signing
     * 2. Timeouts
     * 3. MDC Context
     * 4. Metrics (Timer & Status tags)
     * 5. Error Handling & Logging
     */
    private suspend fun <T> executeOkxCall(
        operationName: String,
        path: String,
        payload: Any,
        timeoutMs: Long,
        contextTags: Map<String, String> = emptyMap(),
        extraMetricTags: Array<String> = emptyArray(),
        responseMapper: (String) -> T
    ): T? {
        var status = "ok"

        // Setup MDC for this execution
        withMdc(contextTags) {
            return meterRegistry.timeOkx(operationName, arrayOf("status", status, *extraMetricTags)) {
                try {
                    withTimeout(timeoutMs) {
                        val url = "$baseUrl$path"
                        val bodyJson = objectMapper.writeValueAsString(payload)
                        val authHeaders = okxAuthService.createAuthHeaders("POST", path, bodyJson)

                        val response: HttpResponse = okxKtorClient.post(url) {
                            authHeaders.forEach { (k, v) -> header(k, v) }
                            contentType(ContentType.Application.Json)
                            setBody(bodyJson)
                        }

                        // Explicitly check HTTP status before parsing body
                        if (!response.status.isSuccess()) {
                            throw ClientRequestException(response, "HTTP ${response.status}")
                        }

                        responseMapper(response.bodyAsText())
                    }
                } catch (e: TimeoutCancellationException) {
                    status("timeout")
                    log.error("Timeout during $operationName",e)
                    null
                } catch (e: ClientRequestException) {
                    status(mapHttpStatusToMetric(e.response.status.value))
                    log.error("HTTP error during $operationName: ${e.response.status}",e)
                    null
                } catch (e: Exception) {
                    status("error")
                    log.error("Unexpected error during $operationName", e)
                    null
                }
            }
        }
    }

    private fun buildOrderPayload(
        instId: String,
        side: String,
        tdMode: String,
        szContracts: String,
        tpPx: String?,
        slPx: String?,
        posSide: String?,
        clOrdId: String?,
        tag: String?
    ): Map<String, Any> = buildMap {
        put("instId", instId)
        put("tdMode", tdMode)
        put("side", side)
        put("ordType", ORD_TYPE_MARKET)
        put("sz", szContracts)

        if (clOrdId != null) put("clOrdId", clOrdId)
        if (tag != null) put("tag", tag)
        if (posSide != null) put("posSide", posSide)

        // Build Attachments (TP/SL)
        val attachments = buildList {
            if (!tpPx.isNullOrBlank()) {
                add(mapOf(
                    "tpTriggerPx" to tpPx,
                    "tpOrdPx" to tpPx,
                    "tpOrdKind" to "limit"
                ))
            }
            if (!slPx.isNullOrBlank()) {
                add(mapOf(
                    "slTriggerPx" to slPx,
                    "slOrdPx" to "-1"
                ))
            }
        }

        if (attachments.isNotEmpty()) {
            put("attachAlgoOrds", attachments)
        }
    }

    private fun handleOrderFailure(
        apiResponse: OkxPlaceOrderApiResponse,
        instId: String,
        clOrdId: String?
    ) {
        val symbol = instId.substringBefore("-")
        BusinessEventLogger.orderRejected(
            symbol = symbol,
            clOrdId = clOrdId,
            reason = apiResponse.msg ?: "Order rejected",
            errorCode = apiResponse.code
        )
        log.warn("Order rejected $clOrdId: code=${apiResponse.code}, msg=${apiResponse.msg}, data=${apiResponse.data}")
    }

    private fun mapHttpStatusToMetric(statusCode: Int): String = when {
        statusCode in 400..499 -> "http_4xx"
        statusCode >= 500 -> "http_5xx"
        else -> "http_error"
    }

    private inline fun <T> withMdc(tags: Map<String, String>, block: () -> T): T {
        val previous = MDC.getCopyOfContextMap() ?: emptyMap()
        tags.forEach { (k, v) -> MDC.put(k, v) }
        try {
            return block()
        } finally {
            // Restore previous context or clear if it was empty
            // Simplification: Just removing the keys we added is often safer if we are in a nested context
            tags.keys.forEach { MDC.remove(it) }
        }
    }
}
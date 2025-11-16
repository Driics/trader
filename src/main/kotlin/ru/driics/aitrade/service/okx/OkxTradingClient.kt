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
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import ru.driics.aitrade.common.timeOkx
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
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
    tradingProperties: TradingProperties
) : OkxClientBase(
    okxProperties, okxKtorClient, okxAuthService, meterRegistry, objectMapper, tradingProperties,
    LoggerFactory.getLogger(OkxTradingClient::class.java)
) {

    @Retry(name = "okxTrade")
    @RateLimiter(name = "okxTrade")
    @CircuitBreaker(name = "okxTrade")
    suspend fun setLeverage(instId: String, leverage: Int, marginMode: MarginMode, posSide: String? = null): Boolean {
        var status = "success"

        return meterRegistry.timeOkx("setLeverage", { arrayOf("status", status) }) {
            try {
                withTimeout(tradingProperties.okxTimeouts.setLeverage.toMillis()) {
                    val path = "/api/v5/account/set-leverage"
                    val url = "$baseUrl$path"
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
                }
            } catch (e: Exception) {
                status = "error"
                log.error("Error setting leverage for $instId", e)
                false
            }
        }
    }

    @Retry(name = "okxTrade")
    @RateLimiter(name = "okxTrade")
    @CircuitBreaker(name = "okxTrade")
    suspend fun placeMarketOrderWithAttach(
        instId: String,
        side: String,
        tdMode: String = "isolated",
        szContracts: String,
        tpPx: String? = null,
        slPx: String? = null,
        posSide: String? = null,
        clOrdId: String? = null,
        tag: String? = "ai-signal"
    ): OkxPlaceOrderData? {
        var status = "success"

        return meterRegistry.timeOkx("placeOrder", { arrayOf("side", side, "status", status) }) {
            val previous = MDC.getCopyOfContextMap()
            try {
                withTimeout(tradingProperties.okxTimeouts.placeOrder.toMillis()) {
                    clOrdId?.let { MDC.put("clOrdId", it) }
                    MDC.put("instId", instId)
                    MDC.put("side", side)

                    val path = "/api/v5/trade/order"
                    val url = "$baseUrl$path"

                    val payload = buildOrderPayload(instId, side, tdMode, szContracts, tpPx, slPx, posSide, clOrdId, tag)
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

                    apiResponse.firstOrNull()
                }
            } catch (e: Exception) {
                status = "error"
                log.error("Error placing order $clOrdId", e)
                null
            } finally {
                if (previous.isNotEmpty())
                    MDC.setContextMap(previous)
                else MDC.clear()
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
    ): MutableMap<String, Any> {
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

        return payload
    }
}


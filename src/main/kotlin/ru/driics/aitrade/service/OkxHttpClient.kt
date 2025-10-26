package ru.driics.aitrade.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.model.*
import java.math.BigDecimal

@Service
class OkxHttpClient(
    private val okxProperties: OkxProperties,
    private val restTemplate: RestTemplate,
    private val okxAuthService: OkxAuthService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchTicker(instId: String): OkxTickerResponse? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/ticker?instId=$instId"
            val response = restTemplate.getForEntity(url, String::class.java).body ?: return null

            val jsonNode = objectMapper.readTree(response)
            validateApiResponse(jsonNode, "ticker")

            val data = jsonNode.get("data")?.get(0) ?: return null

            OkxTickerResponse(
                instrumentId = data.get("instId")?.asText() ?: instId,
                lastPrice = data.get("last")?.asText() ?: "0",
                askPrice = data.get("askPx")?.asText() ?: "0",
                bidPrice = data.get("bidPx")?.asText() ?: "0",
                timestamp = data.get("ts")?.asText() ?: ""
            )
        } catch (e: Exception) {
            log.error("Error fetching ticker for $instId", e)
            null
        }
    }

    fun fetchCandles(instId: String, period: String, limit: Int): List<OkxCandleResponse> {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/candles?instId=$instId&bar=$period&limit=$limit"
            val response = restTemplate.getForEntity(url, String::class.java).body ?: return emptyList()

            val jsonNode = objectMapper.readTree(response)
            validateApiResponse(jsonNode, "candles")

            val candleArray = jsonNode.get("data") ?: return emptyList()

            candleArray.map { candle ->
                val values = candle.map { it.asText() }
                OkxCandleResponse(
                    timestamp = values.getOrNull(0) ?: "",
                    open = values.getOrNull(1) ?: "0",
                    high = values.getOrNull(2) ?: "0",
                    low = values.getOrNull(3) ?: "0",
                    close = values.getOrNull(4) ?: "0",
                    volume = values.getOrNull(5) ?: "0",
                    volumeCcy = values.getOrNull(6) ?: "0"
                )
            }.sortedBy { it.timestamp.toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: Exception) {
            log.error("Error fetching candles for $instId", e)
            emptyList()
        }
    }

    fun fetchFundingRate(instId: String): BigDecimal? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/funding-rate?instId=$instId"
            val response = restTemplate.getForEntity(url, String::class.java).body ?: return null

            val jsonNode = objectMapper.readTree(response)
            validateApiResponse(jsonNode, "funding-rate")

            val data = jsonNode.get("data")?.get(0) ?: return null
            data.get("fundingRate")?.asText()?.toBigDecimalOrNull()
        } catch (e: Exception) {
            log.error("Error fetching funding rate for $instId", e)
            null
        }
    }

    fun fetchOpenInterest(instId: String): BigDecimal? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/open-interest?instId=$instId"
            val response = restTemplate.getForEntity(url, String::class.java).body ?: return null

            val jsonNode = objectMapper.readTree(response)
            validateApiResponse(jsonNode, "open-interest")

            val data = jsonNode.get("data")?.get(0) ?: return null
            data.get("oi")?.asText()?.toBigDecimalOrNull()
        } catch (e: Exception) {
            log.error("Error fetching open interest for $instId", e)
            null
        }
    }

    fun fetchAccount(): OkxAccountResponse {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/account/balance"
            val requestPath = "/api/v5/account/balance"
            val authHeaders = okxAuthService.createAuthHeaders("GET", requestPath)

            val headers = HttpHeaders()
            authHeaders.forEach { (key, value) -> headers.set(key, value) }
            val entity = HttpEntity<String>(headers)

            val response = restTemplate.exchange(url, HttpMethod.GET, entity, String::class.java)
            val body = response.body ?: return OkxAccountResponse("0", "0", "0", "0")

            val jsonNode = objectMapper.readTree(body)
            validateApiResponse(jsonNode, "account")

            val data = jsonNode.get("data")?.get(0) ?: return OkxAccountResponse("0", "0", "0", "0")

            OkxAccountResponse(
                totalEquity = data.get("totalEq")?.asText() ?: "0",
                availableBalance = data.get("availBal")?.asText() ?: "0",
                cashBalance = data.get("cashBal")?.asText() ?: "0",
                unrealizedPnl = data.get("upl")?.asText() ?: "0"
            )
        } catch (e: Exception) {
            log.error("Error fetching account info", e)
            OkxAccountResponse("0", "0", "0", "0")
        }
    }

    fun fetchOpenPositions(): List<OkxPositionResponse> {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/account/positions?instType=SWAP"
            val requestPath = "/api/v5/account/positions?instType=SWAP"
            val authHeaders = okxAuthService.createAuthHeaders("GET", requestPath)

            val headers = HttpHeaders()
            authHeaders.forEach { (key, value) -> headers.set(key, value) }
            val entity = HttpEntity<String>(headers)

            val response = restTemplate.exchange(url, HttpMethod.GET, entity, String::class.java)
            val body = response.body ?: return emptyList()

            val jsonNode = objectMapper.readTree(body)
            validateApiResponse(jsonNode, "positions")

            val dataArray = jsonNode.get("data") ?: return emptyList()

            dataArray.mapNotNull { position ->
                try {
                    OkxPositionResponse(
                        instrumentId = position.get("instId")?.asText() ?: return@mapNotNull null,
                        positionId = position.get("posId")?.asText() ?: "",
                        positionSide = position.get("posSide")?.asText() ?: "",
                        quantity = position.get("pos")?.asText() ?: "0",
                        averagePrice = position.get("avgPx")?.asText() ?: "0",
                        marginMode = position.get("mgnMode")?.asText() ?: "",
                        leverage = position.get("lever")?.asText() ?: "1",
                        liquidationPrice = position.get("liqPx")?.asText() ?: "0",
                        markPrice = position.get("markPx")?.asText() ?: "0",
                        unrealizedPnl = position.get("upl")?.asText() ?: "0",
                        unrealizedPnlRatio = position.get("uplRatio")?.asText() ?: "0",
                        createTime = position.get("cTime")?.asText() ?: "",
                        updateTime = position.get("uTime")?.asText() ?: ""
                    )
                } catch (e: Exception) {
                    log.error("Error parsing position", e)
                    null
                }
            }
        } catch (e: Exception) {
            log.error("Error fetching open positions", e)
            emptyList()
        }
    }

    private fun validateApiResponse(jsonNode: JsonNode, endpoint: String) {
        val code = jsonNode.get("code")?.asText()
        if (code != "0") {
            val msg = jsonNode.get("msg")?.asText() ?: "Unknown error"
            log.warn("OKX API error at $endpoint - Code: $code, Message: $msg")
        }
    }
}
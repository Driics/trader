package ru.driics.aitrade.service

import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
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
    private val okxAuthService: OkxAuthService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchTicker(instId: String): OkxTickerResponse? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/ticker?instId=$instId"

            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxTickerResponse>>() {}
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                responseType
            )

            val apiResponse = response?.body ?: run {
                log.warn("Received null response for ticker: $instId")
                return null
            }

            if (!apiResponse.isSuccess()) {
                log.warn("OKX API error for ticker $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return null
            }

            apiResponse.getFirstOrNull() ?: run {
                log.warn("No ticker data found for $instId")
                null
            }
        } catch (e: Exception) {
            log.error("Error fetching ticker for $instId", e)
            null
        }
    }

    fun fetchCandles(instId: String, period: String, limit: Int): List<OkxCandleResponse> {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/candles?instId=$instId&bar=$period&limit=$limit"

            val responseType = object : ParameterizedTypeReference<OkxCandlesApiResponse>() {}
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                responseType
            )

            val apiResponse = response?.body ?: run {
                log.warn("Received null response for candles: $instId, period: $period")
                return emptyList()
            }

            if (!apiResponse.isSuccess()) {
                log.warn("OKX API error for candles $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return emptyList()
            }

            apiResponse.toCandles().sortedBy { it.timestamp.toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: Exception) {
            log.error("Error fetching candles for $instId, period: $period", e)
            emptyList()
        }
    }

    fun fetchFundingRate(instId: String): BigDecimal? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/funding-rate?instId=$instId"

            // ✅ Use ParameterizedTypeReference to preserve generic type info
            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxFundingResponse>>() {}
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                responseType
            )

            val apiResponse = response?.body ?: run {
                log.warn("Received null response for funding rate: $instId")
                return null
            }

            if (!apiResponse.isSuccess()) {
                log.warn("OKX API error for funding rate $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return null
            }

            apiResponse.getFirstOrNull()?.fundingRate?.toBigDecimalOrNull() ?: run {
                log.debug("No funding rate data for $instId")
                null
            }
        } catch (e: Exception) {
            log.error("Error fetching funding rate for $instId", e)
            null
        }
    }

    fun fetchOpenInterest(instId: String): BigDecimal? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/open-interest?instId=$instId"

            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxOpenInterestResponse>>() {}
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                responseType
            )

            val apiResponse = response?.body ?: run {
                log.warn("Received null response for open interest: $instId")
                return null
            }

            if (!apiResponse.isSuccess()) {
                log.warn("OKX API error for open interest $instId - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return null
            }

            apiResponse.getFirstOrNull()?.openInterest?.toBigDecimalOrNull() ?: run {
                log.debug("No open interest data for $instId")
                null
            }
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

            val responseType = object : ParameterizedTypeReference<OkxAccountApiResponse>() {}
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                responseType
            )

            val apiResponse = response?.body ?: run {
                log.warn("Received null response body for account")
                return OkxAccountResponse("0", "0", "0", "0")
            }

            if (!apiResponse.isSuccess()) {
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

            return OkxAccountResponse(
                totalEquity = totalEq.toPlainString(),
                availableBalance = derivedAvailable.toPlainString(),
                cashBalance = data.cashBalance, // kept as-is (per-ccy)
                unrealizedPnl = data.unrealizedPnl
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

            // ✅ Use ParameterizedTypeReference for generic response
            val responseType = object : ParameterizedTypeReference<OkxApiResponse<OkxPositionResponse>>() {}
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                responseType
            )

            val apiResponse = response?.body ?: run {
                log.warn("Received null response body for positions")
                return emptyList()
            }

            if (!apiResponse.isSuccess()) {
                log.warn("OKX API error for positions - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                return emptyList()
            }

            apiResponse.data.ifEmpty {
                log.debug("No open positions found")
            }

            apiResponse.data
        } catch (e: Exception) {
            log.error("Error fetching open positions", e)
            emptyList()
        }
    }
}
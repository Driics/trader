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
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.service.OkxAuthService
import java.math.BigDecimal

/**
 * Client for OKX account operations (account balance, positions).
 */
@Component
class OkxAccountClient(
    okxProperties: OkxProperties,
    okxKtorClient: HttpClient,
    okxAuthService: OkxAuthService,
    meterRegistry: MeterRegistry,
    objectMapper: ObjectMapper,
    tradingProperties: TradingProperties
) : OkxClientBase(
    okxProperties, okxKtorClient, okxAuthService, meterRegistry, objectMapper, tradingProperties,
    LoggerFactory.getLogger(OkxAccountClient::class.java)
) {

    @Retry(name = "okxAccount")
    @RateLimiter(name = "okxAccount")
    @CircuitBreaker(name = "okxAccount")
    suspend fun fetchAccount(): OkxAccountResponse {
        var status = "ok"

        return meterRegistry.timeOkx("fetchAccount", { arrayOf("status", status) }) {
            try {
                withTimeout(tradingProperties.okxTimeouts.account.toMillis()) {
                    val path = "/api/v5/account/balance"
                    val url = "$baseUrl$path"
                    val authHeaders = okxAuthService.createAuthHeaders("GET", path)

                    val response: HttpResponse = okxKtorClient.get(url) {
                        authHeaders.forEach { (key, value) -> header(key, value) }
                    }

                    val body = response.bodyAsText()
                    val apiResponse = objectMapper.readValue<OkxAccountApiResponse>(body)

                    if (!apiResponse.isSuccess()) {
                        val statusCode = response.status.value
                        status = when {
                            statusCode in 400..499 -> "http_4xx"
                            statusCode >= 500 -> "http_5xx"
                            else -> "api_error"
                        }
                        log.warn("OKX API error for account - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                        return@withTimeout OkxAccountResponse("0", "0", "0", "0")
                    }

                    val data = apiResponse.getFirstOrNull()
                        ?: return@withTimeout OkxAccountResponse("0", "0", "0", "0")

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
                }
            } catch (e: TimeoutCancellationException) {
                status = "timeout"
                OkxAccountResponse("0", "0", "0", "0")
            } catch (e: ClientRequestException) {
                val statusCode = e.response.status.value
                status = when {
                    statusCode in 400..499 -> "http_4xx"
                    statusCode >= 500 -> "http_5xx"
                    else -> "http_error"
                }
                log.error("HTTP error fetching account info: ${e.response.status}", e)
                OkxAccountResponse("0", "0", "0", "0")
            } catch (e: Exception) {
                status = "error"
                log.error("Error fetching account info", e)
                OkxAccountResponse("0", "0", "0", "0")
            }
        }
    }

    @Retry(name = "okxAccount")
    @RateLimiter(name = "okxAccount")
    @CircuitBreaker(name = "okxAccount")
    suspend fun fetchOpenPositions(): List<OkxPositionResponse> {
        var status = "ok"

        return meterRegistry.timeOkx("fetchOpenPositions", { arrayOf("status", status) }) {
            try {
                withTimeout(tradingProperties.okxTimeouts.positions.toMillis()) {
                    val path = "/api/v5/account/positions?instType=SWAP"
                    val url = "$baseUrl$path"
                    val authHeaders = okxAuthService.createAuthHeaders("GET", path)

                    val response: HttpResponse = okxKtorClient.get(url) {
                        authHeaders.forEach { (key, value) -> header(key, value) }
                    }

                    val body = response.bodyAsText()
                    val apiResponse = objectMapper.readValue<OkxApiResponse<OkxPositionResponse>>(body)

                    if (!apiResponse.isSuccess()) {
                        val statusCode = response.status.value
                        status = when {
                            statusCode in 400..499 -> "http_4xx"
                            statusCode >= 500 -> "http_5xx"
                            else -> "api_error"
                        }
                        log.warn("OKX API error for positions - Code: ${apiResponse.code}, Message: ${apiResponse.message}")
                        return@withTimeout emptyList()
                    }

                    apiResponse.data.ifEmpty {
                        log.debug("No open positions found")
                    }

                    apiResponse.data
                }
            } catch (e: TimeoutCancellationException) {
                status = "timeout"
                emptyList()
            } catch (e: ClientRequestException) {
                val statusCode = e.response.status.value
                status = when {
                    statusCode in 400..499 -> "http_4xx"
                    statusCode >= 500 -> "http_5xx"
                    else -> "http_error"
                }
                log.error("HTTP error fetching open positions: ${e.response.status}", e)
                emptyList()
            } catch (e: Exception) {
                status = "error"
                log.error("Error fetching open positions", e)
                emptyList()
            }
        }
    }
}


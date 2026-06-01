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

    private companion object {
        const val METRIC_NAME = "okxAccount"
        const val PATH_BALANCE = "/api/v5/account/balance"
        const val PATH_POSITIONS = "/api/v5/account/positions"
        const val PATH_POSITIONS_HISTORY = "/api/v5/account/positions-history"
        const val PATH_BILLS = "/api/v5/account/bills"
        const val INST_TYPE_SWAP = "SWAP"

        val EMPTY_ACCOUNT = OkxAccountResponse("0", "0", "0", "0")
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun fetchAccount(): OkxAccountResponse {
        return executeSignedRequest(
            operation = "fetchAccount",
            path = PATH_BALANCE,
            timeoutMs = tradingProperties.okxTimeouts.account.toMillis(),
            defaultResult = EMPTY_ACCOUNT
        ) { body ->
            val response = objectMapper.readValue<OkxAccountApiResponse>(body)

            if (!response.isSuccess()) {
                //logApiError("Account Balance", response)
                return@executeSignedRequest EMPTY_ACCOUNT
            }

            val data = response.getFirstOrNull() ?: return@executeSignedRequest EMPTY_ACCOUNT
            mapAccountData(data)
        }
    }

    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun fetchOpenPositions(): List<OkxPositionResponse> {
        return executeSignedRequest(
            operation = "fetchOpenPositions",
            path = "$PATH_POSITIONS?instType=$INST_TYPE_SWAP",
            timeoutMs = tradingProperties.okxTimeouts.positions.toMillis(),
            defaultResult = emptyList()
        ) { body ->
            val response = objectMapper.readValue<OkxApiResponse<OkxPositionResponse>>(body)

            if (!response.isSuccess()) {
                logApiError("Open Positions", response)
                emptyList()
            } else {
                if (response.data.isEmpty()) {
                    log.debug("No open positions found")
                }
                response.data
            }
        }
    }

    /**
     * Fetches account bills (last 7 days), newest-first, one page at a time.
     *
     * Returns the parsed [OkxApiResponse] on a successful HTTP read (the caller must still
     * check `isSuccess()` for an API-level error), or `null` when the read itself failed
     * (timeout / transport / HTTP error). A `null` lets callers fail CLOSED — see
     * [ru.driics.aitrade.infra.exchange.OkxExchangeAdapter.getTodaysRealizedPnlUsd].
     *
     * @param after pagination cursor: returns records strictly older than this billId.
     * @param limit page size (OKX max 100).
     */
    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun fetchBills(after: String? = null, limit: Int = 100): OkxApiResponse<OkxBillData>? {
        val path = buildString {
            append(PATH_BILLS)
            append("?instType=").append(INST_TYPE_SWAP)
            append("&limit=").append(limit)
            if (!after.isNullOrBlank()) append("&after=").append(after)
        }
        return executeSignedRequest<OkxApiResponse<OkxBillData>?>(
            operation = "fetchBills",
            path = path,
            timeoutMs = tradingProperties.okxTimeouts.bills.toMillis(),
            defaultResult = null
        ) { body ->
            objectMapper.readValue<OkxApiResponse<OkxBillData>>(body)
        }
    }

    /**
     * Fetches closed-position history (newest-first), one page at a time. Serves as the independent
     * realized-PnL oracle for reconciling the bills-based daily-loss accounting (B0): per OKX,
     * `realizedPnl = pnl + fee + fundingFee + liqPenalty`.
     *
     * Returns the parsed [OkxApiResponse] on a successful HTTP read (caller still checks `isSuccess()`),
     * or `null` when the read itself failed (timeout / transport / HTTP error).
     *
     * @param after pagination cursor: returns records strictly older than this `posId`.
     * @param limit page size (OKX max 100).
     */
    @Retry(name = METRIC_NAME)
    @RateLimiter(name = METRIC_NAME)
    @CircuitBreaker(name = METRIC_NAME)
    suspend fun fetchPositionsHistory(after: String? = null, limit: Int = 100): OkxApiResponse<OkxPositionHistoryData>? {
        val path = buildString {
            append(PATH_POSITIONS_HISTORY)
            append("?instType=").append(INST_TYPE_SWAP)
            append("&limit=").append(limit)
            if (!after.isNullOrBlank()) append("&after=").append(after)
        }
        return executeSignedRequest<OkxApiResponse<OkxPositionHistoryData>?>(
            operation = "fetchPositionsHistory",
            path = path,
            timeoutMs = tradingProperties.okxTimeouts.positions.toMillis(),
            defaultResult = null
        ) { body ->
            objectMapper.readValue<OkxApiResponse<OkxPositionHistoryData>>(body)
        }
    }

    // =========================================================================
    // Private Logic
    // =========================================================================

    private fun mapAccountData(data: OkxAccountData): OkxAccountResponse {
        val totalEq = data.totalEquity.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val availEqUsd = data.availableEquityUsd.toBigDecimalOrNull() ?: BigDecimal.ZERO

        // Logic: Prefer availableEquityUsd, fallback to USDT available balance
        val derivedAvailable = if (availEqUsd > BigDecimal.ZERO) {
            availEqUsd
        } else {
            data.details.firstOrNull { it.currency.equals("USDT", ignoreCase = true) }
                ?.availableBalance?.toBigDecimalOrNull()
                ?: BigDecimal.ZERO
        }

        return OkxAccountResponse(
            totalEquity = totalEq.toPlainString(),
            availableBalance = derivedAvailable.toPlainString(),
            cashBalance = data.cashBalance,
            unrealizedPnl = data.unrealizedPnl
        )
    }

    /**
     * Generalized executor for Signed GET requests.
     * Handles Authentication, Timeouts, Metrics, and Error Mapping.
     */
    private suspend inline fun <T> executeSignedRequest(
        operation: String,
        path: String,
        timeoutMs: Long,
        defaultResult: T,
        crossinline block: (String) -> T
    ): T {
        // Use TimerScope extension for dynamic tagging
        return meterRegistry.timeOkx(operation) {
            try {
                withTimeout(timeoutMs) {
                    // Generate Sign Headers
                    // Note: For GET requests, body is empty string
                    val authHeaders = okxAuthService.createAuthHeaders("GET", path)

                    val response = okxKtorClient.get("$baseUrl$path") {
                        authHeaders.forEach { (k, v) -> header(k, v) }
                    }

                    if (!response.status.isSuccess()) {
                        val code = response.status.value
                        status(mapHttpStatus(code)) // Dynamic Tag
                        log.warn("HTTP $code for $operation")
                        return@withTimeout defaultResult
                    }

                    block(response.bodyAsText())
                }
            } catch (e: TimeoutCancellationException) {
                status("timeout")
                log.error("Timeout fetching $operation after ${timeoutMs}ms", e)
                defaultResult
            } catch (e: ClientRequestException) {
                status(mapHttpStatus(e.response.status.value))
                log.error("HTTP error fetching $operation", e)
                defaultResult
            } catch (e: Exception) {
                status("error")
                log.error("Unexpected error fetching $operation", e)
                defaultResult
            }
        }
    }

    private fun logApiError(context: String, response: OkxApiResponse<*>) {
        log.warn("OKX API Error [$context]: code=${response.code}, msg=${response.message}")
    }
    // mapHttpStatus is inherited from OkxClientBase (was a duplicate private copy here).
}
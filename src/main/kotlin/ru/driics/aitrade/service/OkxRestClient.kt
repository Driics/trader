package ru.driics.aitrade.service

import org.springframework.stereotype.Service
import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.service.okx.OkxAccountClient
import ru.driics.aitrade.service.okx.OkxMarketDataClient
import ru.driics.aitrade.service.okx.OkxTradingClient
import java.math.BigDecimal

/**
 * Facade for OKX REST API operations.
 * Delegates to specialized clients for better code organization.
 */
@Service
class OkxRestClient(
    private val marketDataClient: OkxMarketDataClient,
    private val accountClient: OkxAccountClient,
    private val tradingClient: OkxTradingClient
) {

    // Market Data Operations
    suspend fun fetchTicker(instId: String) = marketDataClient.fetchTicker(instId)
    suspend fun fetchCandles(instId: String, period: String, limit: Int) = marketDataClient.fetchCandles(instId, period, limit)
    suspend fun fetchFundingRate(instId: String) = marketDataClient.fetchFundingRate(instId)
    suspend fun fetchOpenInterest(instId: String) = marketDataClient.fetchOpenInterest(instId)
    suspend fun getSwapInstrument(instId: String) = marketDataClient.getSwapInstrument(instId)

    // Account Operations
    suspend fun fetchAccount() = accountClient.fetchAccount()
    suspend fun fetchOpenPositions() = accountClient.fetchOpenPositions()
    suspend fun fetchBills(after: String? = null, limit: Int = 100) = accountClient.fetchBills(after, limit)
    suspend fun fetchPositionsHistory(after: String? = null, limit: Int = 100) = accountClient.fetchPositionsHistory(after, limit)

    // Trading Operations
    suspend fun setLeverage(instId: String, leverage: Int, marginMode: MarginMode, posSide: String? = null) =
        tradingClient.setLeverage(instId, leverage, marginMode, posSide)

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
    ) = tradingClient.placeMarketOrderWithAttach(instId, side, tdMode, szContracts, tpPx, slPx, posSide, clOrdId, tag)
}
package ru.driics.aitrade.infra.exchange

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PlaceOrderOutcome
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.model.MarketState
import ru.driics.aitrade.model.OkxInstrumentInfo
import ru.driics.aitrade.service.OkxRestClient
import ru.driics.aitrade.service.OkxMarketDataService
import ru.driics.aitrade.service.OkxTradingService
import java.math.BigDecimal

@Service
class OkxExchangeAdapter(
    private val market: OkxMarketDataService,
    private val tradingService: OkxTradingService,
    private val rest: OkxRestClient
): MarketDataPort, TradingPort {
    override suspend fun loadMarketState(symbols: List<String>): MarketState = withContext(Dispatchers.IO) {
        val currencies = market.fetchMarketData(symbols)
        val account = market.fetchAccountInfo()
        val positions = market.fetchPositions()

        MarketState(
            timestamp = System.currentTimeMillis(),
            minutesSinceStart = (System.currentTimeMillis() - market.getSessionStartTime()) / 60000,
            invocationCount = market.getInvocationCount(),
            currencies = currencies,
            account = account,
            positions = positions
        )
    }

    override suspend fun loadInstrument(instId: String): OkxInstrumentInfo? =
        withContext(Dispatchers.IO) { tradingService.loadInstrument(instId) }

    override suspend fun getLastPrice(instId: String): BigDecimal? = withContext(Dispatchers.IO) {
        val t = rest.fetchTicker(instId)
        t?.lastPrice?.toBigDecimalOrNull() ?: t?.askPrice?.toBigDecimalOrNull() ?: t?.bidPrice?.toBigDecimalOrNull()
    }

    override suspend fun setCrossLeverage(instId: String, leverage: Int): Boolean =
        withContext(Dispatchers.IO) { tradingService.setCrossLeverage(instId, leverage) }

    override suspend fun placeMarketOrderWithTpSl(
        instId: String,
        side: String,
        contracts: BigDecimal,
        tp: BigDecimal?,
        sl: BigDecimal?,
        tickSz: BigDecimal,
        clOrdId: String,
        tag: String?
    ): PlaceOrderOutcome = withContext(Dispatchers.IO) {
        val (ok, ordId) = tradingService.placeMarketOrderWithTpSl(instId, side, contracts, tp, sl, tickSz, clOrdId)
        PlaceOrderOutcome(ok = ok, ordId = ordId, message = if (ok) "OK" else "Rejected")
    }
}
package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import ru.driics.aitrade.domain.types.InstrumentId
import ru.driics.aitrade.domain.types.TradeResult
import java.math.BigDecimal

/**
 * Port for trading operations.
 */
interface TradingPort {
    suspend fun loadInstrument(instrumentId: InstrumentId): TradeResult<OkxInstrumentInfo>

    suspend fun getLastPrice(instrumentId: InstrumentId): TradeResult<BigDecimal?>

    suspend fun setLeverage(
        instrumentId: InstrumentId,
        leverage: Int,
        marginMode: MarginMode
    ): TradeResult<Boolean>

    suspend fun placeMarketOrderWithTpSl(
        instrumentId: InstrumentId,
        side: String,
        contracts: BigDecimal,
        tp: BigDecimal?,
        sl: BigDecimal?,
        tickSz: BigDecimal,
        clOrdId: String,
        tag: String? = null,
        marginMode: MarginMode
    ): TradeResult<PlaceOrderOutcome>

    /**
     * Returns the sum of realized PnL (USD-equivalent) for fills closed since
     * the start of the current UTC day. Returns ZERO when there are no closing
     * fills today. Implementations must not throw on transient exchange errors —
     * surface them via TradeResult failure so callers can fail-open.
     */
    suspend fun getTodaysRealizedPnlUsd(now: java.time.Instant): TradeResult<java.math.BigDecimal>
}

data class PlaceOrderOutcome(
    val ok: Boolean,
    val ordId: String?,
    val message: String?
)
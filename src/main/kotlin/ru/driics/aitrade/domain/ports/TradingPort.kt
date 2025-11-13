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
}

data class PlaceOrderOutcome(
    val ok: Boolean,
    val ordId: String?,
    val message: String?
)
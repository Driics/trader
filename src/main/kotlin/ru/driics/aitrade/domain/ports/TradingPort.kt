package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.domain.types.TradeResult
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import java.math.BigDecimal

interface TradingPort {
    suspend fun loadInstrument(instId: String): TradeResult<OkxInstrumentInfo>

    suspend fun getLastPrice(instId: String): TradeResult<BigDecimal?>

    suspend fun setLeverage(instId: String, leverage: Int, marginMode: MarginMode): TradeResult<Boolean>

    suspend fun placeMarketOrderWithTpSl(
        instId: String,
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

package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.model.OkxInstrumentInfo
import java.math.BigDecimal

interface TradingPort {
    suspend fun loadInstrument(instId: String): OkxInstrumentInfo?

    suspend fun getLastPrice(instId: String): BigDecimal?

    suspend fun setLeverage(instId: String, leverage: Int, marginMode: MarginMode): Boolean

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
    ): PlaceOrderOutcome
}

data class PlaceOrderOutcome(
    val ok: Boolean,
    val ordId: String?,
    val message: String?
)

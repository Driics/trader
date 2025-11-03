package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.model.OkxInstrumentInfo
import java.math.BigDecimal

interface TradingPort {
    suspend fun loadInstrument(instId: String): OkxInstrumentInfo?

    suspend fun getLastPrice(instId: String): BigDecimal?

    suspend fun setCrossLeverage(instId: String, leverage: Int): Boolean

    suspend fun placeMarketOrderWithTpSl(
        instId: String,
        side: String,
        contracts: BigDecimal,
        tp: BigDecimal?,
        sl: BigDecimal?,
        tickSz: BigDecimal,
        clOrdId: String,
        tag: String? = null
    ): PlaceOrderOutcome
}

data class PlaceOrderOutcome(
    val ok: Boolean,
    val ordId: String?,
    val message: String?
)

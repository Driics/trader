package ru.driics.aitrade.domain.journal

import ru.driics.aitrade.domain.model.TradingMode
import java.math.BigDecimal

/** A closed position captured from OKX positions-history. `posId` is unique per close. */
data class JournaledClose(
    val recordedAtMs: Long,
    val posId: String,
    val instId: String,
    val symbol: String,
    val side: String?,            // "long" / "short" (OKX `direction`), nullable if absent
    val realizedPnl: BigDecimal,  // OKX realizedPnl = pnl + fee + fundingFee + liqPenalty
    val openTimeMs: Long,
    val closeTimeMs: Long,
    val mode: TradingMode,
    val demo: Boolean,
)

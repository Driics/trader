package ru.driics.aitrade.domain.model

import java.math.BigDecimal

/** A position OKX has fully closed. `closeTimeMs` is the high-water-mark key for incremental capture. */
data class ClosedPosition(
    val posId: String,
    val instId: String,
    val side: String?,
    val realizedPnl: BigDecimal,
    val openTimeMs: Long,
    val closeTimeMs: Long,
)

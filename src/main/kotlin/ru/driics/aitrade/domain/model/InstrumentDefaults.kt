package ru.driics.aitrade.domain.model

import java.math.BigDecimal

/**
 * Fallback instrument specs used when OKX omits a field. Single source of truth so ActionGuard's
 * pre-quantization defaults and the use case's plan-construction fallbacks can't silently drift apart.
 */
object InstrumentDefaults {
    val TICK_SIZE: BigDecimal = BigDecimal("0.01")
    val LOT_SIZE: BigDecimal = BigDecimal.ONE
}

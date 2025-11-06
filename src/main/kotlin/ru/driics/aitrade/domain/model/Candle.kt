package ru.driics.aitrade.domain.model

import java.math.BigDecimal

/**
 * Domain model for a candlestick.
 */
data class Candle(
    val timestamp: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal
)
package ru.driics.aitrade.domain.backtest

import java.math.BigDecimal

/**
 * One OHLCV candle in a backtest series. [timestampMs] is the bar's open time (epoch millis); a
 * series is expected to be sorted ascending by it.
 */
data class Bar(
    val timestampMs: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal = BigDecimal.ZERO,
)

package ru.driics.aitrade.domain.util

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.isPositive(): Boolean = this > BigDecimal.ZERO

fun BigDecimal.isNegative(): Boolean = this < BigDecimal.ZERO

fun BigDecimal.formatMoney(scale: Int = 2): String =
    this.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

fun BigDecimal.quantize(tickSize: BigDecimal): BigDecimal {
    require(tickSize > BigDecimal.ZERO) { "tickSize must be positive, got: $tickSize" }
    val steps = this.divide(tickSize, 0, RoundingMode.HALF_UP)
    return steps.multiply(tickSize).stripTrailingZeros()
}
package ru.driics.aitrade.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Extension functions for BigDecimal to reduce code duplication.
 */
fun BigDecimal.max(other: BigDecimal): BigDecimal =
    if (this >= other) this else other

fun BigDecimal.min(other: BigDecimal): BigDecimal =
    if (this <= other) this else other

fun BigDecimal.isPositive(): Boolean = this > BigDecimal.ZERO

fun BigDecimal.isNegative(): Boolean = this < BigDecimal.ZERO

fun BigDecimal.formatMoney(scale: Int = 2): String =
    this.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

fun BigDecimal.quantize(tickSize: BigDecimal): BigDecimal {
    if (tickSize <= BigDecimal.ZERO) return this
    val steps = this.divide(tickSize, 0, RoundingMode.HALF_UP)
    return steps.multiply(tickSize).stripTrailingZeros()
}
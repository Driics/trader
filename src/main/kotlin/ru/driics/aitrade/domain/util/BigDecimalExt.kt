package ru.driics.aitrade.domain.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Extension functions for BigDecimal operations.
 * Provides idiomatic Kotlin patterns for common financial calculations.
 */

fun BigDecimal.isPositive(): Boolean = this > BigDecimal.ZERO

fun BigDecimal.isNegative(): Boolean = this < BigDecimal.ZERO

fun BigDecimal.isZeroOrNegative(): Boolean = this <= BigDecimal.ZERO

fun BigDecimal.formatMoney(scale: Int = 2): String =
    this.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/**
 * Quantizes a price to the nearest tick size.
 */
fun BigDecimal.quantize(tickSize: BigDecimal): BigDecimal {
    require(tickSize.isPositive()) { "tickSize must be positive, got: $tickSize" }
    val steps = this.divide(tickSize, 0, RoundingMode.HALF_UP)
    return steps.multiply(tickSize).stripTrailingZeros()
}

/**
 * Quantizes quantity to the nearest lot size.
 */
fun BigDecimal.quantizeToLot(lotSize: BigDecimal): BigDecimal {
    require(lotSize.isPositive()) { "lotSize must be positive, got: $lotSize" }
    return this.divide(lotSize, 0, RoundingMode.DOWN)
        .multiply(lotSize)
        .setScale(lotSize.scale(), RoundingMode.DOWN)
}

/**
 * Safely converts String to BigDecimal, returning null if invalid.
 */
fun String?.toBigDecimalOrNull(): BigDecimal? = try {
    this?.toBigDecimal()
} catch (e: NumberFormatException) {
    null
}

/**
 * Validates that BigDecimal is within range [min, max].
 */
fun BigDecimal.isInRange(min: BigDecimal, max: BigDecimal): Boolean =
    this >= min && this <= max

/**
 * Coerces BigDecimal to be within range [min, max].
 */
fun BigDecimal.coerceInRange(min: BigDecimal, max: BigDecimal): BigDecimal =
    this.coerceAtLeast(min).coerceAtMost(max)
package ru.driics.aitrade.domain.services

import ru.driics.aitrade.model.OkxCandleResponse
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure domain service for technical indicator calculations.
 * No dependencies, stateless, highly testable.
 */
object IndicatorCalculator {
    fun calculateEMA(prices: List<BigDecimal>, period: Int): BigDecimal {
        if (prices.isEmpty() || period <= 0) return BigDecimal.ZERO
        if (prices.size < period) {
            return prices.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(prices.size), 10, RoundingMode.HALF_UP)
        }

        val k = BigDecimal(2).divide(BigDecimal(period + 1), 10, RoundingMode.HALF_UP)
        var ema = prices.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)

        for (i in period until prices.size) {
            ema = prices[i].multiply(k).add(ema.multiply(BigDecimal.ONE.subtract(k)))
        }

        return ema
    }

    fun calculateMACD(prices: List<BigDecimal>): BigDecimal {
        if (prices.size < 26) return BigDecimal.ZERO
        val ema12 = calculateEMA(prices, 12)
        val ema26 = calculateEMA(prices, 26)
        return ema12 - ema26
    }

    fun calculateRSI(prices: List<BigDecimal>, period: Int): BigDecimal {
        if (prices.size < period + 1) return BigDecimal.ZERO

        val changes = prices.zipWithNext { a, b -> b - a }
        val gains = changes.map { if (it > BigDecimal.ZERO) it else BigDecimal.ZERO }
        val losses = changes.map { if (it < BigDecimal.ZERO) it.abs() else BigDecimal.ZERO }

        if (gains.size < period) return BigDecimal.ZERO

        var avgGain = gains.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
        var avgLoss = losses.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)

        for (i in period until gains.size) {
            avgGain = (avgGain.multiply(BigDecimal(period - 1)).add(gains[i]))
                .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
            avgLoss = (avgLoss.multiply(BigDecimal(period - 1)).add(losses[i]))
                .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
        }

        return if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal(100)
        } else {
            val rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP)
            BigDecimal(100).subtract(
                BigDecimal(100).divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP)
            )
        }
    }

    fun calculateATR(candles: List<OkxCandleResponse>, period: Int): BigDecimal {
        if (candles.size < 2 || period <= 0) return BigDecimal.ZERO

        val trueRanges = mutableListOf<BigDecimal>()

        for (i in 1 until candles.size) {
            val high = candles[i].high.toBigDecimalOrNull() ?: continue
            val low = candles[i].low.toBigDecimalOrNull() ?: continue
            val prevClose = candles[i-1].close.toBigDecimalOrNull() ?: continue

            val tr = maxOf(
                high - low,
                (high - prevClose).abs(),
                (low - prevClose).abs()
            )
            trueRanges.add(tr)
        }

        if (trueRanges.isEmpty()) return BigDecimal.ZERO

        return if (trueRanges.size < period) {
            trueRanges.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(trueRanges.size), 10, RoundingMode.HALF_UP)
        } else {
            var atr = trueRanges.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
            for (i in period until trueRanges.size) {
                atr = (atr.multiply(BigDecimal(period - 1)).add(trueRanges[i]))
                    .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
            }
            atr
        }
    }

    // Progressive calculations
    fun calculateProgressiveEMA(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (prices.size < period) return emptyList()

        val result = mutableListOf<BigDecimal>()
        val k = BigDecimal(2).divide(BigDecimal(period + 1), 10, RoundingMode.HALF_UP)

        var ema = prices.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
        result.add(ema)

        for (i in period until prices.size) {
            ema = prices[i].multiply(k).add(ema.multiply(BigDecimal.ONE.subtract(k)))
            result.add(ema)
        }

        return result
    }

    fun calculateProgressiveMACD(prices: List<BigDecimal>): List<BigDecimal> {
        if (prices.size < 26) return emptyList()

        val ema12List = calculateProgressiveEMA(prices, 12)
        val ema26List = calculateProgressiveEMA(prices, 26)
        val alignedEma12 = ema12List.drop(14)
        return alignedEma12.zip(ema26List) { ema12, ema26 -> ema12 - ema26 }
    }

    fun calculateProgressiveRSI(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (prices.size < period + 1) return emptyList()

        val result = mutableListOf<BigDecimal>()
        val changes = prices.zipWithNext { a, b -> b - a }

        val gains = changes.map { if (it > BigDecimal.ZERO) it else BigDecimal.ZERO }
        val losses = changes.map { if (it < BigDecimal.ZERO) it.abs() else BigDecimal.ZERO }

        if (gains.size < period) return emptyList()

        var avgGain = gains.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
        var avgLoss = losses.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)

        val firstRsi = if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal(100)
        } else {
            val rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP)
            BigDecimal(100).subtract(
                BigDecimal(100).divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP)
            )
        }
        result.add(firstRsi)

        for (i in period until gains.size) {
            avgGain = (avgGain.multiply(BigDecimal(period - 1)).add(gains[i]))
                .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)
            avgLoss = (avgLoss.multiply(BigDecimal(period - 1)).add(losses[i]))
                .divide(BigDecimal(period), 10, RoundingMode.HALF_UP)

            val rsi = if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal(100)
            } else {
                val rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP)
                BigDecimal(100).subtract(
                    BigDecimal(100).divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP)
                )
            }
            result.add(rsi)
        }

        return result
    }
}
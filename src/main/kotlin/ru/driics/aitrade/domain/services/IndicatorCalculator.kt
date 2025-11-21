package ru.driics.aitrade.domain.services

import ru.driics.aitrade.domain.model.OkxCandleResponse
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure domain service for technical indicator calculations.
 * Stateless, high-precision, and optimized for readability.
 */
object IndicatorCalculator {

    private const val SCALE = 10
    private val ROUNDING = RoundingMode.HALF_UP

    private val BD_100 = BigDecimal(100)
    private val BD_TWO = BigDecimal(2)

    // =========================================================================
    // Scalar Calculations (Return single latest value)
    // =========================================================================

    fun calculateEMA(prices: List<BigDecimal>, period: Int): BigDecimal {
        if (prices.isEmpty() || period <= 0) return BigDecimal.ZERO

        // If not enough data for a full period, return Simple Moving Average (SMA)
        if (prices.size < period) {
            return prices.average()
        }

        val k = calculateEmaMultiplier(period)
        val periodBd = period.toBigDecimal()

        // 1. Initialize with SMA of the first 'period' elements
        var ema = prices.take(period)
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(periodBd, SCALE, ROUNDING)

        // 2. Apply smoothing for the rest
        for (i in period until prices.size) {
            // EMA = Price(t) * k + EMA(y) * (1 – k)
            ema = (prices[i] * k) + (ema * (BigDecimal.ONE - k))
        }

        return ema.setScale(SCALE, ROUNDING)
    }

    fun calculateMACD(prices: List<BigDecimal>): BigDecimal {
        if (prices.size < 26) return BigDecimal.ZERO
        val ema12 = calculateEMA(prices, 12)
        val ema26 = calculateEMA(prices, 26)
        return (ema12 - ema26).setScale(SCALE, ROUNDING)
    }

    fun calculateRSI(prices: List<BigDecimal>, period: Int): BigDecimal {
        if (prices.size <= period) return BigDecimal.ZERO

        val changes = prices.zipWithNext { a, b -> b - a }
        val gains = changes.map { if (it > BigDecimal.ZERO) it else BigDecimal.ZERO }
        val losses = changes.map { if (it < BigDecimal.ZERO) it.abs() else BigDecimal.ZERO }

        val periodBd = period.toBigDecimal()
        val periodMinusOneBd = (period - 1).toBigDecimal()

        // Initial SMA
        var avgGain = gains.take(period).average(periodBd)
        var avgLoss = losses.take(period).average(periodBd)

        // Wilder's Smoothing
        for (i in period until gains.size) {
            avgGain = ((avgGain * periodMinusOneBd) + gains[i]).divide(periodBd, SCALE, ROUNDING)
            avgLoss = ((avgLoss * periodMinusOneBd) + losses[i]).divide(periodBd, SCALE, ROUNDING)
        }

        return calculateRsiFromAverages(avgGain, avgLoss)
    }

    fun calculateATR(candles: List<OkxCandleResponse>, period: Int): BigDecimal {
        if (candles.size < 2 || period <= 0) return BigDecimal.ZERO

        // Parse and Calculate True Ranges
        val trueRanges = calculateTrueRanges(candles)
        if (trueRanges.isEmpty()) return BigDecimal.ZERO

        val periodBd = period.toBigDecimal()

        if (trueRanges.size < period) {
            return trueRanges.average()
        }

        // Initial ATR is SMA of first 'period' True Ranges
        var atr = trueRanges.take(period).average(periodBd)
        val periodMinusOneBd = (period - 1).toBigDecimal()

        // Wilder's Smoothing for ATR
        for (i in period until trueRanges.size) {
            atr = ((atr * periodMinusOneBd) + trueRanges[i]).divide(periodBd, SCALE, ROUNDING)
        }

        return atr.setScale(SCALE, ROUNDING)
    }

    // =========================================================================
    // Progressive Calculations (Return list of values)
    // =========================================================================

    fun calculateProgressiveEMA(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (prices.size < period) return emptyList()

        val result = ArrayList<BigDecimal>(prices.size - period + 1)
        val k = calculateEmaMultiplier(period)
        val periodBd = period.toBigDecimal()

        // Initial SMA
        var ema = prices.take(period)
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(periodBd, SCALE, ROUNDING)

        result.add(ema)

        for (i in period until prices.size) {
            ema = (prices[i] * k) + (ema * (BigDecimal.ONE - k))
            result.add(ema.setScale(SCALE, ROUNDING))
        }

        return result
    }

    fun calculateProgressiveMACD(prices: List<BigDecimal>): List<BigDecimal> {
        if (prices.size < 26) return emptyList()

        val ema12List = calculateProgressiveEMA(prices, 12) // Starts at index 11
        val ema26List = calculateProgressiveEMA(prices, 26) // Starts at index 25

        // We need to align them.
        // EMA26[0] corresponds to price[25].
        // EMA12[0] corresponds to price[11].
        // We need EMA12 corresponding to price[25], which is at index (25 - 11) = 14.

        val offset = 26 - 12
        if (ema12List.size <= offset) return emptyList()

        val alignedEma12 = ema12List.drop(offset)

        return alignedEma12.zip(ema26List) { e12, e26 ->
            (e12 - e26).setScale(SCALE, ROUNDING)
        }
    }

    fun calculateProgressiveRSI(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (prices.size <= period) return emptyList()

        val result = ArrayList<BigDecimal>(prices.size - period)
        val changes = prices.zipWithNext { a, b -> b - a }

        val gains = changes.map { if (it > BigDecimal.ZERO) it else BigDecimal.ZERO }
        val losses = changes.map { if (it < BigDecimal.ZERO) it.abs() else BigDecimal.ZERO }

        val periodBd = period.toBigDecimal()
        val periodMinusOneBd = (period - 1).toBigDecimal()

        // Initial SMA
        var avgGain = gains.take(period).average(periodBd)
        var avgLoss = losses.take(period).average(periodBd)

        result.add(calculateRsiFromAverages(avgGain, avgLoss))

        for (i in period until gains.size) {
            avgGain = ((avgGain * periodMinusOneBd) + gains[i]).divide(periodBd, SCALE, ROUNDING)
            avgLoss = ((avgLoss * periodMinusOneBd) + losses[i]).divide(periodBd, SCALE, ROUNDING)
            result.add(calculateRsiFromAverages(avgGain, avgLoss))
        }

        return result
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    private fun calculateEmaMultiplier(period: Int): BigDecimal {
        // k = 2 / (N + 1)
        return BD_TWO.divide((period + 1).toBigDecimal(), SCALE, ROUNDING)
    }

    private fun calculateRsiFromAverages(avgGain: BigDecimal, avgLoss: BigDecimal): BigDecimal {
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BD_100
        }
        val rs = avgGain.divide(avgLoss, SCALE, ROUNDING)
        // RSI = 100 - (100 / (1 + RS))
        val denominator = BigDecimal.ONE + rs
        val result = BD_100 - (BD_100.divide(denominator, SCALE, ROUNDING))
        return result.setScale(SCALE, ROUNDING)
    }

    private fun calculateTrueRanges(candles: List<OkxCandleResponse>): List<BigDecimal> {
        val ranges = ArrayList<BigDecimal>(candles.size - 1)

        for (i in 1 until candles.size) {
            val current = candles[i]
            val prev = candles[i - 1]

            // Safe parsing
            val high = current.high.toBigDecimalOrNull() ?: continue
            val low = current.low.toBigDecimalOrNull() ?: continue
            val prevClose = prev.close.toBigDecimalOrNull() ?: continue

            // TR = Max(High-Low, |High-PrevClose|, |Low-PrevClose|)
            val hl = high - low
            val hpc = (high - prevClose).abs()
            val lpc = (low - prevClose).abs()

            ranges.add(hl.max(hpc).max(lpc))
        }
        return ranges
    }

    private fun Iterable<BigDecimal>.average(divisor: BigDecimal? = null): BigDecimal {
        val sum = this.fold(BigDecimal.ZERO, BigDecimal::add)
        val div = divisor ?: this.count().toBigDecimal()
        return if (div.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO
        else sum.divide(div, SCALE, ROUNDING)
    }
}
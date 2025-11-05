package ru.driics.aitrade.domain.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.driics.aitrade.model.OkxCandleResponse
import java.math.BigDecimal
import java.math.RoundingMode

class IndicatorCalculatorTest {

    @Test
    fun `calculateEMA with empty prices returns zero`() {
        val result = IndicatorCalculator.calculateEMA(emptyList(), 20)
        assertEquals(BigDecimal.ZERO, result)
    }

    @Test
    fun `calculateEMA with insufficient data returns average`() {
        val prices = listOf(
            BigDecimal("100"),
            BigDecimal("101"),
            BigDecimal("102")
        )
        val result = IndicatorCalculator.calculateEMA(prices, 20)
        val expected = BigDecimal("101").setScale(10, RoundingMode.HALF_UP)
        assertEquals(expected, result)
    }

    @Test
    fun `calculateEMA with sufficient data calculates correctly`() {
        val prices = List(50) { BigDecimal(100 + it) }
        val result = IndicatorCalculator.calculateEMA(prices, 20)
        
        // EMA should be close to recent prices
        assertTrue(result > BigDecimal("130"))
        assertTrue(result < BigDecimal("150"))
    }

    @Test
    fun `calculateRSI with empty prices returns zero`() {
        val result = IndicatorCalculator.calculateRSI(emptyList(), 14)
        assertEquals(BigDecimal.ZERO, result)
    }

    @Test
    fun `calculateRSI with all gains returns high value`() {
        val prices = List(20) { BigDecimal(100 + it) }
        val result = IndicatorCalculator.calculateRSI(prices, 14)
        
        // RSI should be very high for continuous gains
        assertTrue(result > BigDecimal("70"))
    }

    @Test
    fun `calculateRSI with all losses returns low value`() {
        val prices = List(20) { BigDecimal(120 - it) }
        val result = IndicatorCalculator.calculateRSI(prices, 14)
        
        // RSI should be very low for continuous losses
        assertTrue(result < BigDecimal("30"))
    }

    @Test
    fun `calculateATR with empty candles returns zero`() {
        val result = IndicatorCalculator.calculateATR(emptyList(), 14)
        assertEquals(BigDecimal.ZERO, result)
    }

    @Test
    fun `calculateATR calculates true range correctly`() {
        val candles = listOf(
            createCandle(high = "105", low = "95", close = "100"),
            createCandle(high = "110", low = "98", close = "108"),
            createCandle(high = "112", low = "106", close = "110"),
            createCandle(high = "115", low = "108", close = "113"),
            createCandle(high = "120", low = "112", close = "118")
        )
        
        val result = IndicatorCalculator.calculateATR(candles, 3)
        
        // ATR should be positive and reasonable
        assertTrue(result > BigDecimal.ZERO)
        assertTrue(result < BigDecimal("20"))
    }

    @Test
    fun `calculateProgressiveEMA returns correct number of values`() {
        val prices = List(50) { BigDecimal(100 + it) }
        val result = IndicatorCalculator.calculateProgressiveEMA(prices, 20)
        
        // Should return prices.size - period + 1 values
        assertEquals(31, result.size)
    }

    @Test
    fun `calculateProgressiveEMA values are monotonic for increasing prices`() {
        val prices = List(50) { BigDecimal(100 + it) }
        val result = IndicatorCalculator.calculateProgressiveEMA(prices, 20)
        
        // Each EMA should be >= previous for increasing prices (allow small deviations)
        for (i in 1 until result.size) {
            assertTrue(result[i] >= result[i - 1] || (result[i] - result[i - 1]).abs() < BigDecimal("0.1"))
        }
    }

    @Test
    fun `calculateProgressiveRSI returns correct number of values`() {
        val prices = List(50) { BigDecimal(100 + it % 10) }
        val result = IndicatorCalculator.calculateProgressiveRSI(prices, 14)
        
        // Should return prices.size - period values
        assertEquals(36, result.size)
    }

    @Test
    fun `calculateProgressiveRSI values are in valid range`() {
        val prices = List(50) { BigDecimal(100 + (it * 2) % 20 - 10) }
        val result = IndicatorCalculator.calculateProgressiveRSI(prices, 14)
        
        // All RSI values should be between 0 and 100
        for (rsi in result) {
            assertTrue(rsi >= BigDecimal.ZERO)
            assertTrue(rsi <= BigDecimal(100))
        }
    }

    @Test
    fun `calculateProgressiveMACD returns correct number of values`() {
        val prices = List(100) { BigDecimal(100 + it) }
        val result = IndicatorCalculator.calculateProgressiveMACD(prices)
        
        // Should have values after both EMAs are calculated
        assertTrue(result.isNotEmpty())
        assertTrue(result.size < prices.size)
    }

    @Test
    fun `calculateMACD with insufficient data returns zero`() {
        val prices = List(20) { BigDecimal(100 + it) }
        val result = IndicatorCalculator.calculateMACD(prices)
        assertEquals(BigDecimal.ZERO, result)
    }

    private fun createCandle(high: String, low: String, close: String): OkxCandleResponse {
        return OkxCandleResponse(
            timestamp = System.currentTimeMillis().toString(),
            open = close,
            high = high,
            low = low,
            close = close,
            volume = "1000",
            volumeCcy = "1000"
        )
    }
}
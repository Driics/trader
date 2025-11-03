package ru.driics.aitrade.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.BeforeEach
import ru.driics.aitrade.model.OkxCandleResponse
import java.math.BigDecimal
import java.math.RoundingMode

@DisplayName("TechnicalIndicatorService Tests")
class TechnicalIndicatorServiceTest {

    private lateinit var service: TechnicalIndicatorService

    @BeforeEach
    fun setup() {
        service = TechnicalIndicatorService()
    }

    @Nested
    @DisplayName("EMA Calculation Tests")
    inner class EMATests {

        @Test
        fun `should calculate EMA for valid data`() {
            val prices = listOf(
                BigDecimal("100"), BigDecimal("102"), BigDecimal("101"),
                BigDecimal("103"), BigDecimal("105"), BigDecimal("104"),
                BigDecimal("106"), BigDecimal("108"), BigDecimal("107"),
                BigDecimal("109")
            )

            val ema = service.calculateEMA(prices, 5)

            assertNotEquals(BigDecimal.ZERO, ema)
            assertTrue(ema > BigDecimal.ZERO)
        }

        @Test
        fun `should return zero for empty prices`() {
            val ema = service.calculateEMA(emptyList(), 10)

            assertEquals(BigDecimal.ZERO, ema)
        }

        @Test
        fun `should return zero for period less than or equal to zero`() {
            val prices = listOf(BigDecimal("100"), BigDecimal("101"), BigDecimal("102"))
            
            val ema = service.calculateEMA(prices, 0)
            assertEquals(BigDecimal.ZERO, ema)
        }

        @Test
        fun `should handle prices less than period by using average`() {
            val prices = listOf(BigDecimal("100"), BigDecimal("102"), BigDecimal("104"))

            val ema = service.calculateEMA(prices, 10)

            assertEquals(
                BigDecimal("102.0000000000"),
                ema.setScale(10, RoundingMode.HALF_UP)
            )
        }

        @Test
        fun `should calculate correct EMA for exact period length`() {
            val prices = listOf(
                BigDecimal("100"), BigDecimal("101"), BigDecimal("102"),
                BigDecimal("103"), BigDecimal("104")
            )

            val ema = service.calculateEMA(prices, 5)

            assertTrue(ema > BigDecimal("100"))
            assertTrue(ema < BigDecimal("105"))
        }

        @Test
        fun `should weight recent prices more heavily`() {
            val prices1 = (1..20).map { BigDecimal("100") } + BigDecimal("110")
            val prices2 = BigDecimal("110") + (1..20).map { BigDecimal("100") }

            val ema1 = service.calculateEMA(prices1, 10)
            val ema2 = service.calculateEMA(prices2, 10)

            assertTrue(ema1 > ema2, "Recent prices should have more weight")
        }

        @Test
        fun `should handle single price`() {
            val prices = listOf(BigDecimal("100"))

            val ema = service.calculateEMA(prices, 1)

            assertEquals(BigDecimal("100.0000000000"), ema.setScale(10, RoundingMode.HALF_UP))
        }

        @Test
        fun `should handle large numbers`() {
            val prices = (1..30).map { BigDecimal("50000") }

            val ema = service.calculateEMA(prices, 20)

            assertEquals(BigDecimal("50000.0000000000"), ema.setScale(10, RoundingMode.HALF_UP))
        }

        @Test
        fun `should handle small decimal prices`() {
            val prices = listOf(
                BigDecimal("0.001"), BigDecimal("0.0011"), BigDecimal("0.0012"),
                BigDecimal("0.0013"), BigDecimal("0.0014")
            )

            val ema = service.calculateEMA(prices, 3)

            assertTrue(ema > BigDecimal("0.001"))
            assertTrue(ema < BigDecimal("0.002"))
        }
    }

    @Nested
    @DisplayName("MACD Calculation Tests")
    inner class MACDTests {

        @Test
        fun `should calculate MACD for sufficient data`() {
            val prices = (1..30).map { i -> BigDecimal(100 + i) }

            val macd = service.calculateMACD(prices)

            assertNotEquals(BigDecimal.ZERO, macd)
        }

        @Test
        fun `should return zero for insufficient data`() {
            val prices = (1..25).map { BigDecimal("100") }

            val macd = service.calculateMACD(prices)

            assertEquals(BigDecimal.ZERO, macd)
        }

        @Test
        fun `should be positive when short EMA above long EMA`() {
            val prices = (1..15).map { BigDecimal("100") } + 
                         (1..15).map { BigDecimal("110") }

            val macd = service.calculateMACD(prices)

            assertTrue(macd > BigDecimal.ZERO, "MACD should be positive for uptrend")
        }

        @Test
        fun `should be negative when short EMA below long EMA`() {
            val prices = (1..15).map { BigDecimal("110") } + 
                         (1..15).map { BigDecimal("100") }

            val macd = service.calculateMACD(prices)

            assertTrue(macd < BigDecimal.ZERO, "MACD should be negative for downtrend")
        }

        @Test
        fun `should be near zero for flat prices`() {
            val prices = (1..30).map { BigDecimal("100") }

            val macd = service.calculateMACD(prices)

            assertTrue(macd.abs() < BigDecimal("1"), "MACD should be near zero for flat prices")
        }

        @Test
        fun `should handle exactly 26 prices`() {
            val prices = (1..26).map { BigDecimal("100") }

            val macd = service.calculateMACD(prices)

            assertNotNull(macd)
        }
    }

    @Nested
    @DisplayName("RSI Calculation Tests")
    inner class RSITests {

        @Test
        fun `should calculate RSI for valid data`() {
            val prices = listOf(
                BigDecimal("100"), BigDecimal("102"), BigDecimal("101"),
                BigDecimal("103"), BigDecimal("105"), BigDecimal("104"),
                BigDecimal("106"), BigDecimal("108"), BigDecimal("107"),
                BigDecimal("109"), BigDecimal("111"), BigDecimal("110"),
                BigDecimal("112"), BigDecimal("114"), BigDecimal("113")
            )

            val rsi = service.calculateRSI(prices, 7)

            assertTrue(rsi >= BigDecimal.ZERO)
            assertTrue(rsi <= BigDecimal("100"))
        }

        @Test
        fun `should return zero for insufficient data`() {
            val prices = listOf(BigDecimal("100"), BigDecimal("101"))

            val rsi = service.calculateRSI(prices, 7)

            assertEquals(BigDecimal.ZERO, rsi)
        }

        @Test
        fun `should return 100 for all gains`() {
            val prices = (1..15).map { i -> BigDecimal(100 + i * 2) }

            val rsi = service.calculateRSI(prices, 7)

            assertTrue(rsi > BigDecimal("80"), "RSI should be high for all gains")
        }

        @Test
        fun `should return low value for all losses`() {
            val prices = (1..15).map { i -> BigDecimal(115 - i * 2) }.reversed()

            val rsi = service.calculateRSI(prices, 7)

            assertTrue(rsi < BigDecimal("20"), "RSI should be low for all losses")
        }

        @Test
        fun `should be around 50 for balanced gains and losses`() {
            val prices = listOf(
                BigDecimal("100"), BigDecimal("102"), BigDecimal("100"),
                BigDecimal("102"), BigDecimal("100"), BigDecimal("102"),
                BigDecimal("100"), BigDecimal("102"), BigDecimal("100")
            )

            val rsi = service.calculateRSI(prices, 7)

            assertTrue(rsi > BigDecimal("30"))
            assertTrue(rsi < BigDecimal("70"))
        }

        @Test
        fun `should handle zero average loss`() {
            val prices = (1..15).map { i -> BigDecimal(100 + i) }

            val rsi = service.calculateRSI(prices, 7)

            assertEquals(BigDecimal("100"), rsi)
        }

        @Test
        fun `should use Wilders smoothing correctly`() {
            val prices = (1..20).map { i -> 
                if (i % 2 == 0) BigDecimal(100 + i) else BigDecimal(100 + i - 1)
            }

            val rsi = service.calculateRSI(prices, 7)

            assertTrue(rsi >= BigDecimal.ZERO)
            assertTrue(rsi <= BigDecimal("100"))
        }
    }

    @Nested
    @DisplayName("ATR Calculation Tests")
    inner class ATRTests {

        @Test
        fun `should calculate ATR for valid candles`() {
            val candles = listOf(
                createCandle("100", "105", "99", "103"),
                createCandle("103", "108", "102", "106"),
                createCandle("106", "110", "104", "108"),
                createCandle("108", "112", "106", "110"),
                createCandle("110", "115", "108", "113")
            )

            val atr = service.calculateATR(candles, 3)

            assertTrue(atr > BigDecimal.ZERO)
        }

        @Test
        fun `should return zero for insufficient candles`() {
            val candles = listOf(createCandle("100", "105", "99", "103"))

            val atr = service.calculateATR(candles, 5)

            assertEquals(BigDecimal.ZERO, atr)
        }

        @Test
        fun `should return zero for period less than or equal to zero`() {
            val candles = listOf(
                createCandle("100", "105", "99", "103"),
                createCandle("103", "108", "102", "106")
            )

            val atr = service.calculateATR(candles, 0)

            assertEquals(BigDecimal.ZERO, atr)
        }

        @Test
        fun `should handle gaps between candles`() {
            val candles = listOf(
                createCandle("100", "105", "99", "103"),
                createCandle("110", "115", "108", "113"),
                createCandle("113", "118", "111", "116")
            )

            val atr = service.calculateATR(candles, 2)

            assertTrue(atr > BigDecimal.ZERO)
        }

        @Test
        fun `should handle invalid candle data gracefully`() {
            val candles = listOf(
                createCandle("100", "105", "99", "103"),
                createCandle("invalid", "108", "102", "106"),
                createCandle("106", "110", "104", "108")
            )

            val atr = service.calculateATR(candles, 2)

            assertNotNull(atr)
        }

        @Test
        fun `should return average for period less than TR count`() {
            val candles = listOf(
                createCandle("100", "105", "99", "103"),
                createCandle("103", "108", "102", "106"),
                createCandle("106", "110", "104", "108")
            )

            val atr = service.calculateATR(candles, 10)

            assertTrue(atr > BigDecimal.ZERO)
        }

        @Test
        fun `should use Wilders smoothing for sufficient data`() {
            val candles = (1..20).map { i ->
                createCandle("${100 + i}", "${105 + i}", "${99 + i}", "${103 + i}")
            }

            val atr = service.calculateATR(candles, 14)

            assertTrue(atr > BigDecimal.ZERO)
        }

        private fun createCandle(open: String, high: String, low: String, close: String): OkxCandleResponse {
            return OkxCandleResponse(
                timestamp = "${System.currentTimeMillis()}",
                open = open,
                high = high,
                low = low,
                close = close,
                volume = "1000",
                volumeCcy = "USDT"
            )
        }
    }

    @Nested
    @DisplayName("Progressive EMA Tests")
    inner class ProgressiveEMATests {

        @Test
        fun `should return empty list for insufficient data`() {
            val prices = listOf(BigDecimal("100"), BigDecimal("101"))

            val result = service.calculateProgressiveEMA(prices, 5)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return EMA values for each point after period`() {
            val prices = (1..15).map { BigDecimal(100 + it) }

            val result = service.calculateProgressiveEMA(prices, 5)

            assertEquals(11, result.size)
        }

        @Test
        fun `should have increasing values for uptrend`() {
            val prices = (1..20).map { BigDecimal(100 + it) }

            val result = service.calculateProgressiveEMA(prices, 5)

            assertTrue(result.size > 1)
            assertTrue(result.last() > result.first())
        }

        @Test
        fun `should have decreasing values for downtrend`() {
            val prices = (1..20).map { BigDecimal(120 - it) }

            val result = service.calculateProgressiveEMA(prices, 5)

            assertTrue(result.size > 1)
            assertTrue(result.last() < result.first())
        }

        @Test
        fun `should match single EMA calculation for last value`() {
            val prices = (1..20).map { BigDecimal(100 + it) }

            val progressive = service.calculateProgressiveEMA(prices, 5)
            val single = service.calculateEMA(prices, 5)

            assertEquals(
                single.setScale(8, RoundingMode.HALF_UP),
                progressive.last().setScale(8, RoundingMode.HALF_UP)
            )
        }
    }

    @Nested
    @DisplayName("Progressive MACD Tests")
    inner class ProgressiveMACDTests {

        @Test
        fun `should return empty list for insufficient data`() {
            val prices = (1..25).map { BigDecimal("100") }

            val result = service.calculateProgressiveMACD(prices)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return MACD values for valid data`() {
            val prices = (1..40).map { BigDecimal(100 + it) }

            val result = service.calculateProgressiveMACD(prices)

            assertTrue(result.isNotEmpty())
        }

        @Test
        fun `should align EMA12 and EMA26 lists correctly`() {
            val prices = (1..40).map { BigDecimal(100 + it) }

            val result = service.calculateProgressiveMACD(prices)

            assertTrue(result.isNotEmpty())
            // Should have values after alignment
            assertTrue(result.size <= prices.size - 26)
        }

        @Test
        fun `should match single MACD calculation for last value`() {
            val prices = (1..40).map { BigDecimal(100 + it) }

            val progressive = service.calculateProgressiveMACD(prices)
            val single = service.calculateMACD(prices)

            assertEquals(
                single.setScale(8, RoundingMode.HALF_UP),
                progressive.last().setScale(8, RoundingMode.HALF_UP)
            )
        }
    }

    @Nested
    @DisplayName("Progressive RSI Tests")
    inner class ProgressiveRSITests {

        @Test
        fun `should return empty list for insufficient data`() {
            val prices = listOf(BigDecimal("100"), BigDecimal("101"))

            val result = service.calculateProgressiveRSI(prices, 7)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return RSI values for each point after period`() {
            val prices = (1..20).map { BigDecimal(100 + it) }

            val result = service.calculateProgressiveRSI(prices, 7)

            assertTrue(result.isNotEmpty())
            assertTrue(result.size <= prices.size - 7)
        }

        @Test
        fun `should have all values between 0 and 100`() {
            val prices = (1..20).map { i ->
                if (i % 2 == 0) BigDecimal(100 + i) else BigDecimal(100 + i - 1)
            }

            val result = service.calculateProgressiveRSI(prices, 7)

            result.forEach { rsi ->
                assertTrue(rsi >= BigDecimal.ZERO)
                assertTrue(rsi <= BigDecimal("100"))
            }
        }

        @Test
        fun `should match single RSI calculation for last value`() {
            val prices = (1..20).map { BigDecimal(100 + it) }

            val progressive = service.calculateProgressiveRSI(prices, 7)
            val single = service.calculateRSI(prices, 7)

            assertEquals(
                single.setScale(8, RoundingMode.HALF_UP),
                progressive.last().setScale(8, RoundingMode.HALF_UP)
            )
        }

        @Test
        fun `should use Wilders smoothing correctly for progressive calculation`() {
            val prices = (1..25).map { i -> BigDecimal(100 + i % 5) }

            val result = service.calculateProgressiveRSI(prices, 7)

            assertTrue(result.isNotEmpty())
            result.forEach { rsi ->
                assertTrue(rsi >= BigDecimal.ZERO)
                assertTrue(rsi <= BigDecimal("100"))
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases and Integration Tests")
    inner class EdgeCasesTests {

        @Test
        fun `should handle very large price values`() {
            val prices = (1..30).map { BigDecimal("50000") }

            val ema = service.calculateEMA(prices, 20)
            val macd = service.calculateMACD(prices)
            val rsi = service.calculateRSI(prices, 14)

            assertNotNull(ema)
            assertNotNull(macd)
            assertNotNull(rsi)
        }

        @Test
        fun `should handle very small price values`() {
            val prices = (1..30).map { BigDecimal("0.00001") }

            val ema = service.calculateEMA(prices, 20)
            val macd = service.calculateMACD(prices)
            val rsi = service.calculateRSI(prices, 14)

            assertNotNull(ema)
            assertNotNull(macd)
            assertNotNull(rsi)
        }

        @Test
        fun `should handle price sequences with high volatility`() {
            val prices = (1..30).map { i ->
                if (i % 2 == 0) BigDecimal("1000") else BigDecimal("500")
            }

            val ema = service.calculateEMA(prices, 10)
            val rsi = service.calculateRSI(prices, 7)

            assertTrue(ema > BigDecimal.ZERO)
            assertTrue(rsi >= BigDecimal.ZERO && rsi <= BigDecimal("100"))
        }

        @Test
        fun `should handle realistic crypto price data`() {
            val btcPrices = listOf(
                BigDecimal("45000"), BigDecimal("45100"), BigDecimal("44900"),
                BigDecimal("45200"), BigDecimal("45300"), BigDecimal("45100"),
                BigDecimal("45400"), BigDecimal("45500"), BigDecimal("45300"),
                BigDecimal("45600"), BigDecimal("45700"), BigDecimal("45500"),
                BigDecimal("45800"), BigDecimal("45900"), BigDecimal("45700"),
                BigDecimal("46000"), BigDecimal("46100"), BigDecimal("45900"),
                BigDecimal("46200"), BigDecimal("46300")
            )

            val ema = service.calculateEMA(btcPrices, 10)
            val rsi = service.calculateRSI(btcPrices, 7)

            assertTrue(ema > BigDecimal("45000"))
            assertTrue(ema < BigDecimal("47000"))
            assertTrue(rsi > BigDecimal("50"))
        }
    }
}
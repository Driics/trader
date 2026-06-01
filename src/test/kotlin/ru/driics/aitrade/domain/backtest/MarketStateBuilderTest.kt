package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.core.Bar
import ru.driics.aitrade.domain.backtest.engine.MarketStateBuilder
import ru.driics.aitrade.domain.model.AccountInfo
import java.math.BigDecimal

class MarketStateBuilderTest {

    private val account = AccountInfo(BigDecimal.ZERO, BigDecimal("1000"), BigDecimal("1000"))

    /** close[i] = (i + 1) * 10 — strictly increasing, so any future leak would show as a larger value. */
    private fun series(n: Int): List<Bar> = (0 until n).map { i ->
        val c = BigDecimal((i + 1) * 10)
        Bar(timestampMs = i.toLong() * 60_000, open = c, high = c, low = c, close = c)
    }

    @Test
    fun `current price is the last bar's close and timestamp is the last bar`() {
        val prefix = series(5) // closes 10,20,30,40,50
        val state = MarketStateBuilder.build("X", prefix, stepIndex = 4, account, emptyList(), intradayWindow = 1000)

        assertEquals(0, BigDecimal("50").compareTo(state.currencies.getValue("X").currentPrice))
        assertEquals(4L * 60_000, state.timestamp)
        assertEquals(4L, state.invocationCount)
    }

    @Test
    fun `intraday prices contain exactly the prefix, in order, with no future leak`() {
        val prefix = series(6).subList(0, 4) // decision at index 3: closes 10,20,30,40
        val md = MarketStateBuilder.build("X", prefix, stepIndex = 3, account, emptyList(), intradayWindow = 1000)
            .currencies.getValue("X")

        // size == i+1 catches off-by-one inclusion even on non-monotonic data
        assertEquals(4, md.intradayPrices.size)
        assertEquals(
            listOf("10", "20", "30", "40").map { BigDecimal(it) },
            md.intradayPrices,
        )
        // 50 belongs to the next (unseen) bar — it must never appear
        assertTrue(md.intradayPrices.none { it.compareTo(BigDecimal("50")) == 0 })
    }

    @Test
    fun `intraday window truncates to the most recent entries`() {
        val prefix = series(10) // closes 10..100
        val md = MarketStateBuilder.build("X", prefix, stepIndex = 9, account, emptyList(), intradayWindow = 3)
            .currencies.getValue("X")

        assertEquals(3, md.intradayPrices.size)
        assertEquals(
            listOf("80", "90", "100").map { BigDecimal(it) },
            md.intradayPrices,
        )
    }

    @Test
    fun `rsi7 matches the IndicatorCalculator over the same closes`() {
        val prefix = series(12)
        val md = MarketStateBuilder.build("X", prefix, stepIndex = 11, account, emptyList(), intradayWindow = 1000)
            .currencies.getValue("X")

        val expected = ru.driics.aitrade.domain.services.IndicatorCalculator
            .calculateRSI(prefix.map { it.close }, 7)
        assertEquals(0, expected.compareTo(md.currentRsi7))
    }
}

package ru.driics.aitrade.domain.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

class DonchianBreakoutStrategyTest {

    /** Mirrors the engine: currentPrice == the last intraday close. */
    private fun state(closes: List<Int>) = MarketState(
        timestamp = 0, minutesSinceStart = 0, invocationCount = 0,
        currencies = mapOf(
            "X" to CurrencyMarketData(
                symbol = "X",
                currentPrice = BigDecimal(closes.last()),
                currentEma20 = BigDecimal.ZERO, currentMacd = BigDecimal.ZERO, currentRsi7 = BigDecimal.ZERO,
                intradayPrices = closes.map { BigDecimal(it) },
            ),
        ),
        account = AccountInfo(BigDecimal.ZERO, BigDecimal("1000"), BigDecimal("1000")),
        positions = emptyList(),
    )

    private val strat = DonchianBreakoutStrategy(channel = 3)

    @Test
    fun `breakout above the prior channel high goes long`() {
        // prior 3 closes [10,11,12]; current 13 > 12 -> BUY
        val d = strat.decide(state(listOf(10, 11, 12, 13))).single()
        assertEquals(AiSignal.BUY, d.signal)
        assertEquals(0, BigDecimal("12.74").compareTo(d.stopLoss!!))    // 13 * (1 - 0.02)
        assertEquals(0, BigDecimal("13.78").compareTo(d.takeProfit!!))  // 13 * (1 + 0.06)
    }

    @Test
    fun `breakdown below the prior channel low goes short`() {
        // prior [20,18,16]; current 15 < 16 -> SELL
        assertEquals(AiSignal.SELL, strat.decide(state(listOf(20, 18, 16, 15))).single().signal)
    }

    @Test
    fun `inside the channel holds`() {
        // prior [10,12,14]; current 13 is between -> HOLD
        assertEquals(AiSignal.HOLD, strat.decide(state(listOf(10, 12, 14, 13))).single().signal)
    }

    @Test
    fun `insufficient history holds`() {
        assertEquals(AiSignal.HOLD, strat.decide(state(listOf(10, 11))).single().signal)
    }
}

package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class PerformanceMetricsTest {

    private fun trade(pnl: String) = SimTrade(
        symbol = "BTC", side = PositionSide.LONG, entryPrice = BigDecimal("100"), exitPrice = BigDecimal("100"),
        quantity = BigDecimal("1"), entryTimestampMs = 0, exitTimestampMs = 1, reason = ExitReason.TAKE_PROFIT,
        pnlUsd = BigDecimal(pnl), feesUsd = BigDecimal.ZERO,
    )

    @Test
    fun `win and loss counts and win rate`() {
        val m = PerformanceMetrics.from(
            BigDecimal("1000"), listOf(BigDecimal("1000")), listOf(trade("10"), trade("-5"), trade("20")),
        )
        assertEquals(3, m.trades)
        assertEquals(2, m.wins)
        assertEquals(1, m.losses)
        assertEquals(0, BigDecimal("66.67").compareTo(m.winRatePct)) // 2/3
    }

    @Test
    fun `profit factor is gross profit over gross loss magnitude`() {
        // wins 10 + 20 = 30, loss magnitude 5 -> PF 6
        val m = PerformanceMetrics.from(
            BigDecimal("1000"), listOf(BigDecimal("1000")), listOf(trade("10"), trade("-5"), trade("20")),
        )
        assertEquals(0, BigDecimal("6").compareTo(m.profitFactor!!))
    }

    @Test
    fun `profit factor is null when there are no losses`() {
        val m = PerformanceMetrics.from(BigDecimal("1000"), listOf(BigDecimal("1000")), listOf(trade("10")))
        assertNull(m.profitFactor)
    }

    @Test
    fun `total return is computed from the equity curve`() {
        // 1000 -> 1100 = +10%
        val m = PerformanceMetrics.from(
            BigDecimal("1000"), listOf(BigDecimal("1000"), BigDecimal("1100")), emptyList(),
        )
        assertEquals(0, BigDecimal("10.00").compareTo(m.totalReturnPct))
    }

    @Test
    fun `max drawdown is the largest peak-to-trough decline`() {
        // 1000 -> 1200 (peak) -> 900 : dd = 300/1200 = 25%, later recovery to 1100 does not exceed it
        val curve = listOf(BigDecimal("1000"), BigDecimal("1200"), BigDecimal("900"), BigDecimal("1100"))
        assertEquals(
            0,
            BigDecimal("25.00").compareTo(PerformanceMetrics.maxDrawdownPct(curve).setScale(2, RoundingMode.HALF_UP)),
        )
    }

    @Test
    fun `empty trades yields zero win rate and does not crash`() {
        val m = PerformanceMetrics.from(BigDecimal("1000"), listOf(BigDecimal("1000")), emptyList())
        assertEquals(0, m.trades)
        assertEquals(0, BigDecimal.ZERO.compareTo(m.winRatePct))
        assertNull(m.profitFactor)
    }
}

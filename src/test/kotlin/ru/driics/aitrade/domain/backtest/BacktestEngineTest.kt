package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.core.Bar
import ru.driics.aitrade.domain.backtest.core.ExitReason
import ru.driics.aitrade.domain.backtest.core.PositionSide
import ru.driics.aitrade.domain.backtest.engine.BacktestConfig
import ru.driics.aitrade.domain.backtest.engine.BacktestEngine
import ru.driics.aitrade.domain.backtest.engine.BacktestRejections
import ru.driics.aitrade.domain.backtest.engine.InstrumentSpec
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.strategy.Strategy
import ru.driics.aitrade.domain.strategy.StrategyDecision
import java.math.BigDecimal

/**
 * End-to-end engine behaviour over a HAND-COMPUTED scenario — the one test that catches loop-ordering /
 * off-by-one bugs (fill -> exit -> mark -> decide) that the pure-unit tests cannot. Fees and buffer are
 * zero and the quantity is explicit so the entire equity curve and trade ledger are checkable by hand.
 *
 * Series (ts = index minute), symbol "X":
 *   i=0  o100 h101 l99  c100
 *   i=1  o100 h105 l100 c104   <- BUY decided here (invocationCount==1)
 *   i=2  o104 h108 l103 c107   <- fills at open=104
 *   i=3  o107 h112 l106 c110   <- high 112 >= TP 110 -> take-profit
 *   i=4  o110 h111 l109 c110
 */
class BacktestEngineTest {

    private val bars = listOf(
        Bar(0, BigDecimal("100"), BigDecimal("101"), BigDecimal("99"), BigDecimal("100")),
        Bar(60_000, BigDecimal("100"), BigDecimal("105"), BigDecimal("100"), BigDecimal("104")),
        Bar(120_000, BigDecimal("104"), BigDecimal("108"), BigDecimal("103"), BigDecimal("107")),
        Bar(180_000, BigDecimal("107"), BigDecimal("112"), BigDecimal("106"), BigDecimal("110")),
        Bar(240_000, BigDecimal("110"), BigDecimal("111"), BigDecimal("109"), BigDecimal("110")),
    )

    private fun config() = BacktestConfig(
        startingEquityUsd = BigDecimal("1000"),
        takerFeePct = BigDecimal.ZERO,
        marginBufferPct = BigDecimal.ZERO,
        riskPerTradePct = BigDecimal("0.01"),
        warmupBars = 0,
        intradayWindow = 1000,
        instruments = mapOf("X" to InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))),
        minLev = 1,
        maxLev = 40,
    )

    /** Emits the given decision once, at the matching invocationCount. */
    private fun buyAt(count: Long, stop: String?, target: String?) = object : Strategy {
        override val name = "scripted"
        override fun decide(state: MarketState): List<StrategyDecision> =
            if (state.invocationCount == count) {
                listOf(
                    StrategyDecision(
                        "X", AiSignal.BUY,
                        stopLoss = stop?.let { BigDecimal(it) },
                        takeProfit = target?.let { BigDecimal(it) },
                        leverage = 5, quantity = BigDecimal("1"),
                    ),
                )
            } else {
                emptyList()
            }
    }

    private fun assertCurve(expected: List<String>, actual: List<BigDecimal>) {
        assertEquals(expected.size, actual.size, "curve length")
        expected.forEachIndexed { i, e ->
            assertEquals(0, BigDecimal(e).compareTo(actual[i]), "equity at step $i")
        }
    }

    @Test
    fun `take-profit round trip produces the hand-computed curve and trade`() {
        val result = BacktestEngine(buyAt(1, stop = "100", target = "110"), config()).run("X", bars)

        assertCurve(listOf("1000", "1000", "1003", "1006", "1006"), result.equityCurve)

        val trade = result.trades.single()
        assertEquals(PositionSide.LONG, trade.side)
        assertEquals(ExitReason.TAKE_PROFIT, trade.reason)
        assertEquals(0, BigDecimal("104").compareTo(trade.entryPrice))
        assertEquals(0, BigDecimal("110").compareTo(trade.exitPrice))
        assertEquals(0, BigDecimal("1").compareTo(trade.quantity))
        assertEquals(120_000, trade.entryTimestampMs)
        assertEquals(180_000, trade.exitTimestampMs)
        assertEquals(0, BigDecimal("6").compareTo(trade.pnlUsd))

        assertEquals(0, BigDecimal("6").compareTo(result.metrics.netPnlUsd))
        assertEquals(0, BigDecimal("0.60").compareTo(result.metrics.totalReturnPct))
        assertEquals(1, result.metrics.wins)
        assertEquals(BacktestRejections(), result.rejections)
    }

    @Test
    fun `the equity curve reconciles with the sum of trade pnl`() {
        val result = BacktestEngine(buyAt(1, stop = "100", target = "110"), config()).run("X", bars)
        val sumPnl = result.trades.fold(BigDecimal.ZERO) { a, t -> a + t.pnlUsd }
        // final marked equity == starting equity + Σ realized pnl  (no silently-excluded open trades)
        assertEquals(0, (BigDecimal("1000") + sumPnl).compareTo(result.equityCurve.last()))
    }

    @Test
    fun `a position open at the last bar is force-closed END_OF_DATA and still reconciles`() {
        // stop 90 / target 130 are never touched, so the position survives to the final bar.
        val result = BacktestEngine(buyAt(1, stop = "90", target = "130"), config()).run("X", bars)

        assertCurve(listOf("1000", "1000", "1003", "1006", "1006"), result.equityCurve)

        val trade = result.trades.single()
        assertEquals(ExitReason.END_OF_DATA, trade.reason)
        assertEquals(0, BigDecimal("104").compareTo(trade.entryPrice))
        assertEquals(0, BigDecimal("110").compareTo(trade.exitPrice)) // forced at final close
        assertEquals(240_000, trade.exitTimestampMs)
        assertEquals(0, BigDecimal("6").compareTo(trade.pnlUsd))

        val sumPnl = result.trades.fold(BigDecimal.ZERO) { a, t -> a + t.pnlUsd }
        assertEquals(0, (BigDecimal("1000") + sumPnl).compareTo(result.equityCurve.last()))
    }

    @Test
    fun `a decision without a stop is rejected on the NO_STOP path and never trades`() {
        val result = BacktestEngine(buyAt(1, stop = null, target = "110"), config()).run("X", bars)

        assertTrue(result.trades.isEmpty())
        assertCurve(listOf("1000", "1000", "1000", "1000", "1000"), result.equityCurve)
        assertEquals(1, result.rejections.noStop)
        assertEquals(0, result.rejections.gappedThroughBracket)
    }

    @Test
    fun `result advertises the known unmodelled costs`() {
        val result = BacktestEngine(buyAt(1, stop = "100", target = "110"), config()).run("X", bars)
        assertTrue(result.omissions.any { it.contains("funding") })
    }
}

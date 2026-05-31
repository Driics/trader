package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.strategy.Strategy
import ru.driics.aitrade.domain.strategy.StrategyDecision
import java.math.BigDecimal

/**
 * Invariants 1 & 2 — the two ways a backtest lies about time — pinned as tests, not comments:
 *  - the strategy at step i sees ONLY bars[0..i] (no look-ahead), and
 *  - an order decided at step i fills at open[i+1], never at the close[i] it was decided on.
 */
class BacktestNoLookAheadTest {

    private fun usdConfig(warmup: Int = 0) = BacktestConfig(
        startingEquityUsd = BigDecimal("100000"),
        takerFeePct = BigDecimal.ZERO,
        marginBufferPct = BigDecimal.ZERO,
        riskPerTradePct = BigDecimal("0.01"),
        warmupBars = warmup,
        intradayWindow = 1000,
        instruments = mapOf("X" to InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))),
        minLev = 1,
        maxLev = 40,
    )

    private data class Seen(val price: BigDecimal, val size: Int, val max: BigDecimal)

    /** Records every snapshot it is handed; never trades, so it only observes the time boundary. */
    private class ProbeStrategy : Strategy {
        override val name = "probe"
        val seen = mutableListOf<Seen>()
        override fun decide(state: MarketState): List<StrategyDecision> {
            val md = state.currencies.getValue("X")
            seen += Seen(md.currentPrice, md.intradayPrices.size, md.intradayPrices.maxOrNull()!!)
            return emptyList()
        }
    }

    @Test
    fun `at each step the strategy sees only bars up to and including that step`() {
        // close[i] = (i+1)*10, strictly increasing: a future leak would surface as a too-large value.
        val bars = (0 until 8).map { i ->
            val c = BigDecimal((i + 1) * 10)
            Bar(i.toLong() * 60_000, c, c, c, c)
        }
        val probe = ProbeStrategy()
        BacktestEngine(probe, usdConfig()).run("X", bars)

        assertEquals(bars.size, probe.seen.size) // decided once per bar (warmup 0)
        probe.seen.forEachIndexed { i, s ->
            val expected = BigDecimal((i + 1) * 10)
            assertEquals(0, expected.compareTo(s.price), "step $i current price")
            assertEquals(i + 1, s.size, "step $i intraday size")
            assertEquals(0, expected.compareTo(s.max), "step $i intraday max never exceeds decision bar")
        }
    }

    @Test
    fun `an order decided at step i fills at the next bar's open, not the decision close`() {
        // Decision fires at i=2 on close[2]=120; the next bar GAPS to open[3]=131.
        val bars = listOf(
            Bar(0, BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal("100")),
            Bar(60_000, BigDecimal("110"), BigDecimal("110"), BigDecimal("110"), BigDecimal("110")),
            Bar(120_000, BigDecimal("120"), BigDecimal("120"), BigDecimal("120"), BigDecimal("120")),
            Bar(180_000, BigDecimal("131"), BigDecimal("140"), BigDecimal("131"), BigDecimal("135")),
            Bar(240_000, BigDecimal("135"), BigDecimal("150"), BigDecimal("135"), BigDecimal("140")),
            Bar(300_000, BigDecimal("140"), BigDecimal("140"), BigDecimal("140"), BigDecimal("140")),
        )
        val strategy = object : Strategy {
            override val name = "buy-at-2"
            override fun decide(state: MarketState): List<StrategyDecision> =
                if (state.invocationCount == 2L) {
                    listOf(
                        StrategyDecision(
                            "X", AiSignal.BUY,
                            stopLoss = BigDecimal("100"), takeProfit = BigDecimal("160"),
                            leverage = 5, quantity = BigDecimal("1"),
                        ),
                    )
                } else {
                    emptyList()
                }
        }

        val result = BacktestEngine(strategy, usdConfig()).run("X", bars)

        assertEquals(1, result.trades.size)
        val trade = result.trades.single()
        assertEquals(0, BigDecimal("131").compareTo(trade.entryPrice)) // open[3], NOT close[2]=120
        assertEquals(180_000, trade.entryTimestampMs)
        assertEquals(ExitReason.END_OF_DATA, trade.reason)
        assertEquals(0, BigDecimal("140").compareTo(trade.exitPrice)) // forced close at last close
        assertTrue(result.rejections == BacktestRejections())
    }
}

package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.core.ExitReason
import ru.driics.aitrade.domain.backtest.core.PerformanceMetrics
import ru.driics.aitrade.domain.backtest.core.PositionSide
import ru.driics.aitrade.domain.backtest.core.SimTrade
import ru.driics.aitrade.domain.backtest.engine.BacktestConfig
import ru.driics.aitrade.domain.backtest.engine.BacktestRejections
import ru.driics.aitrade.domain.backtest.engine.BacktestResult
import ru.driics.aitrade.domain.backtest.engine.InstrumentSpec
import ru.driics.aitrade.domain.backtest.io.BacktestReport
import ru.driics.aitrade.domain.backtest.io.BacktestRunner
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.strategy.Strategy
import ru.driics.aitrade.domain.strategy.StrategyDecision
import java.math.BigDecimal

class BacktestRunnerReportTest {

    @Test
    fun `default config carries the symbol's instrument spec and starting equity`() {
        val config = BacktestRunner.defaultConfig("BTC-USDT-SWAP")
        assertEquals(0, BigDecimal("10000").compareTo(config.startingEquityUsd))
        assertTrue(config.instruments.containsKey("BTC-USDT-SWAP"))
        assertEquals("BTC", config.instruments.getValue("BTC-USDT-SWAP").ctValCcy)
    }

    @Test
    fun `run parses JSONL and drives the engine end to end`() {
        // Same hand-checked 5-bar series as BacktestEngineTest, in OKX candle-array JSONL form.
        val jsonl = """
            ["0","100","101","99","100","0"]
            ["60000","100","105","100","104","0"]
            ["120000","104","108","103","107","0"]
            ["180000","107","112","106","110","0"]
            ["240000","110","111","109","110","0"]
        """.trimIndent()

        val scripted = object : Strategy {
            override val name = "scripted"
            override fun decide(state: MarketState): List<StrategyDecision> =
                if (state.invocationCount == 1L) {
                    listOf(
                        StrategyDecision(
                            "X", AiSignal.BUY,
                            stopLoss = BigDecimal("100"), takeProfit = BigDecimal("110"),
                            leverage = 5, quantity = BigDecimal("1"),
                        ),
                    )
                } else {
                    emptyList()
                }
        }
        val config = BacktestConfig(
            startingEquityUsd = BigDecimal("1000"),
            takerFeePct = BigDecimal.ZERO,
            marginBufferPct = BigDecimal.ZERO,
            riskPerTradePct = BigDecimal("0.02"),   // matches defaultConfig; qty=1 stays within budget (no clamp)
            warmupBars = 0,
            intradayWindow = 1000,
            instruments = mapOf("X" to InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))),
            minLev = 1,
            maxLev = 40,
        )

        val result = BacktestRunner.run(jsonl, "X", strategy = scripted, config = config)

        assertEquals("X", result.symbol)
        assertEquals(5, result.equityCurve.size) // one mark per parsed bar
        assertEquals(1, result.trades.size)
        assertEquals(0, BigDecimal("6").compareTo(result.metrics.netPnlUsd))
    }

    @Test
    fun `report includes performance, declined entries, and the unmodelled-cost footer`() {
        val trade = SimTrade(
            symbol = "BTC-USDT-SWAP", side = PositionSide.LONG,
            entryPrice = BigDecimal("100"), exitPrice = BigDecimal("110"),
            quantity = BigDecimal("10"), entryTimestampMs = 0, exitTimestampMs = 1,
            reason = ExitReason.TAKE_PROFIT, pnlUsd = BigDecimal("100"), feesUsd = BigDecimal.ZERO,
        )
        val metrics = PerformanceMetrics.from(BigDecimal("1000"), listOf(BigDecimal("1100")), listOf(trade))
        val result = BacktestResult(
            symbol = "BTC-USDT-SWAP", strategyName = "rsi-test", metrics = metrics,
            trades = listOf(trade), equityCurve = listOf(BigDecimal("1100")),
            rejections = BacktestRejections(noStop = 2), omissions = BacktestResult.KNOWN_OMISSIONS,
        )

        val text = BacktestReport.render(BigDecimal("1000"), barCount = 1, result = result)
        val lines = text.lines()

        // Line-based (startsWith label / endsWith value) so column padding is not asserted.
        assertTrue(lines.any { it.startsWith("symbol") && it.contains("BTC-USDT-SWAP") }, text)
        assertTrue(lines.any { it.startsWith("strategy") && it.contains("rsi-test") }, text)
        assertTrue(lines.any { it.startsWith("final equity") && it.endsWith("\$1100.00") }, text)
        assertTrue(lines.any { it.startsWith("net PnL") && it.endsWith("\$100.00") }, text)
        assertTrue(lines.any { it.startsWith("total return") && it.endsWith("10.00%") }, text)
        assertTrue(lines.any { it.startsWith("win rate") && it.endsWith("100.00%") }, text)
        assertTrue(lines.any { it.startsWith("profit factor") && it.contains("n/a (no losses)") }, text)
        assertTrue(lines.any { it.startsWith("no stop") && it.endsWith(": 2") }, text)
        assertTrue(text.contains("funding"), text)
    }
}

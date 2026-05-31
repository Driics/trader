package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.engine.BacktestConfig
import ru.driics.aitrade.domain.backtest.engine.InstrumentSpec
import ru.driics.aitrade.domain.backtest.io.BacktestRunner
import java.math.BigDecimal

class AiReplayTest {

    private val candles = """
        ["0","100","101","99","100","0"]
        ["60000","100","105","100","104","0"]
        ["120000","104","108","103","107","0"]
        ["180000","107","112","106","110","0"]
        ["240000","110","111","109","110","0"]
    """.trimIndent()

    // A recorded BUY at bar 1 (ts 60000): fills at open[2]=104, take-profits at 110 -> +6 (cf. BacktestEngineTest).
    private val decisions =
        """{"timestampMs":60000,"symbol":"X","signal":"buy","stopLoss":100,"takeProfit":110,"leverage":5,"quantity":1,"confidence":0.9}"""

    private fun usdConfig() = BacktestConfig(
        startingEquityUsd = BigDecimal("1000"), takerFeePct = BigDecimal.ZERO,
        marginBufferPct = BigDecimal.ZERO, riskPerTradePct = BigDecimal("0.01"),
        warmupBars = 0, intradayWindow = 1000,
        instruments = mapOf("X" to InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))),
        minLev = 1, maxLev = 40,
    )

    @Test
    fun `replayAi parses both files, backtests, and reports a full match`() {
        val outcome = BacktestRunner.replayAi(candles, decisions, "X", config = usdConfig())
        assertEquals(1, outcome.result.trades.size)
        assertEquals(0, BigDecimal("6").compareTo(outcome.result.metrics.netPnlUsd))
        assertEquals(1, outcome.matchStats.recorded)
        assertEquals(1, outcome.matchStats.matched)
        assertEquals(100, outcome.matchStats.matchRatePct)
    }

    @Test
    fun `confidence threshold gates the decision out at replay but it still counts as matched`() {
        val outcome = BacktestRunner.replayAi(candles, decisions, "X", minConfidence = BigDecimal("0.95"), config = usdConfig())
        assertEquals(0, outcome.result.trades.size) // 0.9 < 0.95 -> no trade...
        assertEquals(1, outcome.matchStats.matched) // ...but it DID line up with a candle (not a file mismatch)
    }
}

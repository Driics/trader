package ru.driics.aitrade.domain.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.Bar
import ru.driics.aitrade.domain.backtest.BacktestConfig
import ru.driics.aitrade.domain.backtest.BacktestEngine
import ru.driics.aitrade.domain.backtest.ExitReason
import ru.driics.aitrade.domain.backtest.InstrumentSpec
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.CurrencyMarketData
import java.math.BigDecimal

class RecordedAiStrategyTest {

    private fun stateAt(ts: Long, price: String = "100") = MarketState(
        timestamp = ts, minutesSinceStart = 0, invocationCount = 0,
        currencies = mapOf(
            "X" to CurrencyMarketData("X", BigDecimal(price), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        ),
        account = AccountInfo(BigDecimal.ZERO, BigDecimal("1000"), BigDecimal("1000")),
        positions = emptyList(),
    )

    private fun rec(ts: Long, conf: String?) = RecordedDecision(
        timestampMs = ts, symbol = "X", signal = AiSignal.BUY,
        stopLoss = BigDecimal("100"), takeProfit = BigDecimal("110"), leverage = 5,
        quantity = BigDecimal("1"), confidence = conf?.let { BigDecimal(it) },
    )

    @Test
    fun `returns the recorded decision for a matching timestamp, nothing otherwise`() {
        val s = RecordedAiStrategy(listOf(rec(60_000, conf = "0.9")))
        assertEquals(AiSignal.BUY, s.decide(stateAt(60_000)).single().signal)
        assertTrue(s.decide(stateAt(120_000)).isEmpty()) // no decision recorded at this ts
    }

    @Test
    fun `min confidence gates exactly like the live use case`() {
        val s = RecordedAiStrategy(listOf(rec(1, "0.30")), minConfidence = BigDecimal("0.50"))
        assertTrue(s.decide(stateAt(1)).isEmpty()) // 0.30 < 0.50 -> skipped
        val s2 = RecordedAiStrategy(listOf(rec(1, "0.50")), minConfidence = BigDecimal("0.50"))
        assertEquals(1, s2.decide(stateAt(1)).size) // 0.50 >= 0.50 -> kept (inclusive)
    }

    @Test
    fun `missing confidence counts as zero and is gated out by a positive threshold`() {
        val s = RecordedAiStrategy(listOf(rec(1, conf = null)), minConfidence = BigDecimal("0.10"))
        assertTrue(s.decide(stateAt(1)).isEmpty())
        val s2 = RecordedAiStrategy(listOf(rec(1, conf = null))) // default threshold 0
        assertEquals(1, s2.decide(stateAt(1)).size)
    }

    @Test
    fun `AiTradeSignalArgs maps to a recorded decision (profit_target becomes takeProfit)`() {
        val args = AiTradeSignalArgs(
            coin = "BTC", signal = AiSignal.SELL,
            stopLoss = BigDecimal("105"), profitTarget = BigDecimal("90"),
            leverage = 7, confidence = BigDecimal("0.8"), riskUsd = BigDecimal("50"),
        )
        val r = args.toRecordedDecision(symbol = "BTC-USDT-SWAP", timestampMs = 42)
        assertEquals(42, r.timestampMs)
        assertEquals("BTC-USDT-SWAP", r.symbol)
        assertEquals(AiSignal.SELL, r.signal)
        assertEquals(0, BigDecimal("90").compareTo(r.takeProfit)) // profit_target -> takeProfit
        assertEquals(0, BigDecimal("105").compareTo(r.stopLoss))
        assertEquals(7, r.leverage)
    }

    @Test
    fun `synthetic recorded log drives the engine to the hand-computed trade`() {
        // Same 5-bar series as BacktestEngineTest; a recorded BUY at ts=60000 (bar 1) fills at open[2]=104
        // and take-profits at 110 on bar 3 -> +6.
        val bars = listOf(
            Bar(0, BigDecimal("100"), BigDecimal("101"), BigDecimal("99"), BigDecimal("100")),
            Bar(60_000, BigDecimal("100"), BigDecimal("105"), BigDecimal("100"), BigDecimal("104")),
            Bar(120_000, BigDecimal("104"), BigDecimal("108"), BigDecimal("103"), BigDecimal("107")),
            Bar(180_000, BigDecimal("107"), BigDecimal("112"), BigDecimal("106"), BigDecimal("110")),
            Bar(240_000, BigDecimal("110"), BigDecimal("111"), BigDecimal("109"), BigDecimal("110")),
        )
        val config = BacktestConfig(
            startingEquityUsd = BigDecimal("1000"), takerFeePct = BigDecimal.ZERO,
            marginBufferPct = BigDecimal.ZERO, riskPerTradePct = BigDecimal("0.01"),
            warmupBars = 0, intradayWindow = 1000,
            instruments = mapOf("X" to InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))),
            minLev = 1, maxLev = 40,
        )
        val strategy = RecordedAiStrategy(listOf(rec(60_000, conf = "0.9")))

        val result = BacktestEngine(strategy, config).run("X", bars)

        val trade = result.trades.single()
        assertEquals(ExitReason.TAKE_PROFIT, trade.reason)
        assertEquals(0, BigDecimal("104").compareTo(trade.entryPrice))
        assertEquals(0, BigDecimal("6").compareTo(trade.pnlUsd))
        assertEquals(0, BigDecimal("6").compareTo(result.metrics.netPnlUsd))
    }
}

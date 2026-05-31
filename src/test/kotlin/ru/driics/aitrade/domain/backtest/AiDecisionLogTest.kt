package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.strategy.RecordedDecision
import java.math.BigDecimal

class AiDecisionLogTest {

    private val decisions = listOf(
        RecordedDecision(
            timestampMs = 60_000, symbol = "BTC-USDT-SWAP", signal = AiSignal.BUY,
            stopLoss = BigDecimal("98000"), takeProfit = BigDecimal("104000"), leverage = 5,
            confidence = BigDecimal("0.72"),
        ),
        RecordedDecision(timestampMs = 120_000, symbol = "BTC-USDT-SWAP", signal = AiSignal.HOLD),
    )

    @Test
    fun `serialize then parse round-trips the decisions`() {
        val parsed = AiDecisionLog.parse(AiDecisionLog.serialize(decisions))
        assertEquals(2, parsed.size)
        assertEquals(60_000, parsed[0].timestampMs)
        assertEquals(AiSignal.BUY, parsed[0].signal)
        assertEquals(0, BigDecimal("98000").compareTo(parsed[0].stopLoss))
        assertEquals(0, BigDecimal("0.72").compareTo(parsed[0].confidence))
        assertEquals(5, parsed[0].leverage)
        assertEquals(AiSignal.HOLD, parsed[1].signal)
    }

    @Test
    fun `parse skips blank and comment lines`() {
        val jsonl = """
            # recorded for BTC-USDT-SWAP

            {"timestampMs":60000,"symbol":"X","signal":"buy"}
        """.trimIndent()
        val parsed = AiDecisionLog.parse(jsonl)
        assertEquals(1, parsed.size)
        assertEquals(AiSignal.BUY, parsed[0].signal)
    }

    @Test
    fun `match stats count recorded decisions that land on a candle`() {
        val recorded = setOf(60_000L, 120_000L, 999_000L) // last one has no matching bar
        val bars = listOf(0L, 60_000L, 120_000L, 180_000L)
        val stats = AiDecisionLog.matchStats(recorded, bars)
        assertEquals(3, stats.recorded)
        assertEquals(2, stats.matched)
        assertEquals(1, stats.unmatched)
        assertEquals(66, stats.matchRatePct)
    }
}

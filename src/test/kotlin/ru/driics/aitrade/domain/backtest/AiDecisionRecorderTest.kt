package ru.driics.aitrade.domain.backtest

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AiAnalysisResponse
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeDecisionMap
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal

/** The recorder loop (cadence, no-look-ahead window, mapping, skips) tested with fakes — no LLM. */
class AiDecisionRecorderTest {

    private fun series(n: Int) = (0 until n).map { i ->
        val c = BigDecimal((i + 1) * 10)
        Bar(i.toLong() * 60_000, c, c, c, c)
    }

    private fun config(warmup: Int = 0, lookback: Int = 400) = BacktestConfig(
        startingEquityUsd = BigDecimal("10000"), takerFeePct = BigDecimal("0.0005"),
        marginBufferPct = BigDecimal("0.05"), riskPerTradePct = BigDecimal("0.01"),
        warmupBars = warmup, intradayWindow = 1000, indicatorLookback = lookback,
        instruments = mapOf("X" to InstrumentSpec(BigDecimal("0.01"), "BTC", BigDecimal("0.1"), BigDecimal("0.1"))),
    )

    private fun okResponse(json: String = "{}") = AiAnalysisResponse("fake", "m", json, 1, true)

    private val buyMap: AiTradeDecisionMap = mapOf(
        "X" to AiTradeEnvelope(
            AiTradeSignalArgs(
                coin = "X", signal = AiSignal.BUY, stopLoss = BigDecimal("9"),
                profitTarget = BigDecimal("12"), leverage = 5, confidence = BigDecimal("0.7"),
            ),
        ),
    )

    @Test
    fun `records one decision per bar at cadence 1, stamped with the bar timestamp`() = runBlocking {
        val rec = AiDecisionRecorder(
            symbol = "X", config = config(),
            buildPrompt = { "p" }, analyze = { okResponse() }, parseDecisions = { buyMap }, cadenceBars = 1,
        )
        val out = rec.record(series(5))
        assertEquals(listOf(0L, 60_000L, 120_000L, 180_000L, 240_000L), out.map { it.timestampMs })
        assertEquals(AiSignal.BUY, out[0].signal)
        assertEquals(0, BigDecimal("12").compareTo(out[0].takeProfit)) // profit_target -> takeProfit
        assertEquals(5, out[0].leverage)
    }

    @Test
    fun `warmup and cadence thin the invocations`() = runBlocking {
        val rec = AiDecisionRecorder(
            symbol = "X", config = config(warmup = 1),
            buildPrompt = { "p" }, analyze = { okResponse() }, parseDecisions = { buyMap }, cadenceBars = 2,
        )
        val out = rec.record(series(5)) // i in {1,3} -> 2 records
        assertEquals(listOf(60_000L, 180_000L), out.map { it.timestampMs })
    }

    @Test
    fun `the AI never sees a future bar and the window is bounded`() = runBlocking {
        val seen = mutableListOf<BigDecimal>() // max close handed to the prompt at each step
        val rec = AiDecisionRecorder(
            symbol = "X", config = config(lookback = 3),
            buildPrompt = { st -> seen += st.currencies.getValue("X").intradayPrices.maxOrNull()!!; "p" },
            analyze = { okResponse() }, parseDecisions = { buyMap }, cadenceBars = 1,
        )
        rec.record(series(8))
        // close[i] = (i+1)*10; the max the AI sees at step i must equal its own close, never a later one.
        seen.forEachIndexed { i, maxClose -> assertEquals(0, BigDecimal((i + 1) * 10).compareTo(maxClose)) }
    }

    @Test
    fun `failed responses are skipped and reported`() = runBlocking {
        val skipped = mutableListOf<Int>()
        var n = 0
        val rec = AiDecisionRecorder(
            symbol = "X", config = config(),
            buildPrompt = { "p" },
            analyze = { if (n++ == 2) AiAnalysisResponse("fake", "m", "", 1, false, "boom") else okResponse() },
            parseDecisions = { buyMap }, cadenceBars = 1,
        )
        val out = rec.record(series(5)) { i, _ -> skipped += i }
        assertEquals(4, out.size)
        assertEquals(listOf(2), skipped)
        assertTrue(out.none { it.timestampMs == 120_000L })
    }
}

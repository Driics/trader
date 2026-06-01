package ru.driics.aitrade.infra.recording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import ru.driics.aitrade.domain.backtest.ai.AiDecisionLog
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class JsonlDecisionLogSinkTest {

    @TempDir
    lateinit var tmp: Path

    private fun envelope(coin: String, signal: AiSignal, confidence: String) = AiTradeEnvelope(
        AiTradeSignalArgs(
            coin = coin,
            signal = signal,
            confidence = BigDecimal(confidence),
            riskUsd = BigDecimal("10"),
            stopLoss = BigDecimal("60000"),
            profitTarget = BigDecimal("70000"),
            leverage = 5,
        ),
    )

    @Test
    fun `appends each cycle's decisions as parseable RecordedDecision JSONL`() {
        // nested path also exercises parent-directory creation
        val file = tmp.resolve("nested").resolve("live-decisions.jsonl")
        val sink = JsonlDecisionLogSink(file)

        sink.record(
            mapOf("BTC" to envelope("BTC", AiSignal.BUY, "0.90"), "ETH" to envelope("ETH", AiSignal.HOLD, "0.40")),
            1_000L,
        )
        sink.record(mapOf("SOL" to envelope("SOL", AiSignal.SELL, "0.75")), 2_000L)

        val parsed = AiDecisionLog.parse(Files.readString(file))
        assertEquals(3, parsed.size, "two cycles appended (2 + 1 decisions), HOLD included")

        val btc = parsed.first { it.symbol == "BTC" }
        assertEquals(AiSignal.BUY, btc.signal)
        assertEquals(1_000L, btc.timestampMs)
        assertEquals(0, BigDecimal("70000").compareTo(btc.takeProfit), "profit_target maps to takeProfit")
        assertEquals(0, BigDecimal("0.90").compareTo(btc.confidence))

        val sol = parsed.first { it.symbol == "SOL" }
        assertEquals(2_000L, sol.timestampMs, "second cycle's timestamp is preserved")
        assertEquals(AiSignal.SELL, sol.signal)
    }

    @Test
    fun `empty decisions write nothing`() {
        val file = tmp.resolve("empty.jsonl")
        JsonlDecisionLogSink(file).record(emptyMap(), 1_000L)
        assertTrue(Files.notExists(file), "no file created for an empty decision map")
    }

    @Test
    fun `an IO failure is swallowed, never breaking the cycle`() {
        // `tmp` is a directory; writing to it as if a file fails — the sink must catch and not rethrow.
        assertDoesNotThrow {
            JsonlDecisionLogSink(tmp).record(mapOf("BTC" to envelope("BTC", AiSignal.BUY, "0.9")), 1_000L)
        }
    }
}

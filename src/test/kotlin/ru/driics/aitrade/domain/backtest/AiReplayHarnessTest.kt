package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal

/**
 * Runnable AI-replay entrypoint (offline — no LLM). Backtests a recorded AI decision log over candles and
 * prints + writes the report, including the decision↔candle match-rate and the entry-signals-only caveat.
 * Skipped unless both `BACKTEST_FILE` (candles) and `AI_DECISIONS_FILE` (decision log) are set. The
 * decision log is produced by the recorder (network/LLM, user-run) — see docs/backtest-howto.md.
 *
 *   $env:BACKTEST_FILE="data/btc.jsonl"; $env:AI_DECISIONS_FILE="data/btc-ai.jsonl"
 *   .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*AiReplayHarnessTest*'
 */
class AiReplayHarnessTest {

    @Test
    fun `replay a recorded AI decision log over candles when both files are set`() {
        val candlePath = System.getenv("BACKTEST_FILE")
        val decisionPath = System.getenv("AI_DECISIONS_FILE")
        assumeTrue(
            !candlePath.isNullOrBlank() && !decisionPath.isNullOrBlank(),
            "set BACKTEST_FILE and AI_DECISIONS_FILE to run the AI replay harness",
        )
        val candleFile = File(candlePath!!)
        val decisionFile = File(decisionPath!!)
        assumeTrue(candleFile.isFile && decisionFile.isFile, "candle or decision file not found")

        val symbol = System.getenv("BACKTEST_SYMBOL")?.takeIf { it.isNotBlank() } ?: "BTC-USDT-SWAP"
        val equity = System.getenv("BACKTEST_EQUITY")?.toBigDecimalOrNull() ?: BigDecimal("10000")
        val minConf = System.getenv("AI_MIN_CONFIDENCE")?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        val config = BacktestRunner.defaultConfig(symbol, equity)
        val outcome = BacktestRunner.replayAi(candleFile.readText(), decisionFile.readText(), symbol, minConf, config)
        val m = outcome.matchStats

        val notes = listOf(
            "AI replay: ${m.matched}/${m.recorded} recorded decisions matched a candle (${m.matchRatePct}%)" +
                if (m.matched == 0) "  <-- ZERO match: decision log and candles do not line up!" else "",
            "minConfidence gate: $minConf",
            "ENTRY SIGNALS ONLY — flat-state recording; the AI's hold/close/invalidation and " +
                "drawdown-aware sizing are NOT exercised here",
        )
        val report = BacktestReport.render(equity, outcome.result.equityCurve.size, outcome.result, notes)

        println(report)
        val out = File(decisionFile.absoluteFile.parentFile, decisionFile.nameWithoutExtension + ".replay-report.txt")
        out.writeText(report)
        println("Report written to: ${out.absolutePath}")

        assertTrue(outcome.result.equityCurve.isNotEmpty(), "no bars parsed from $candlePath")
    }
}

package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.io.BacktestRunner
import ru.driics.aitrade.domain.backtest.io.compareModels
import java.io.File
import java.math.BigDecimal

/**
 * Runnable OFFLINE model comparison (no LLM). Replays several recorded AI decision logs over ONE candle
 * file and prints + writes a ranked P&L table — the deterministic "which model makes/loses money" view.
 * Record each model separately (AiRecorderHarnessTest, costs money), then point this at the logs. Skipped
 * unless both env vars are set, so a normal build never runs it.
 *
 *   BACKTEST_FILE       candle JSONL (required)
 *   AI_DECISIONS_FILES  comma-separated decision logs (required). Each entry is `path` (label = filename
 *                       minus a trailing `-ai`) OR `label=path` to name it explicitly.
 *   BACKTEST_SYMBOL     default BTC-USDT-SWAP
 *   BACKTEST_EQUITY     default 10000
 *   AI_MIN_CONFIDENCE   default 0
 *
 *   $env:BACKTEST_FILE="data/btc.jsonl"
 *   $env:AI_DECISIONS_FILES="qwen=data/btc-qwen-ai.jsonl,gpt-oss=data/btc-gpt-oss-ai.jsonl"
 *   .\gradlew.bat test --tests '*ModelComparisonHarnessTest*' --rerun-tasks --console=plain
 */
class ModelComparisonHarnessTest {

    @Test
    fun `compare recorded model decision logs over a candle file`() {
        val candlePath = System.getenv("BACKTEST_FILE")
        val filesEnv = System.getenv("AI_DECISIONS_FILES")
        assumeTrue(
            !candlePath.isNullOrBlank() && !filesEnv.isNullOrBlank(),
            "set BACKTEST_FILE and AI_DECISIONS_FILES to run the model comparison",
        )
        val candleFile = File(candlePath!!)
        assumeTrue(candleFile.isFile, "candle file not found: ${candleFile.absolutePath}")

        val symbol = System.getenv("BACKTEST_SYMBOL")?.takeIf { it.isNotBlank() } ?: "BTC-USDT-SWAP"
        val equity = System.getenv("BACKTEST_EQUITY")?.toBigDecimalOrNull() ?: BigDecimal("10000")
        val minConf = System.getenv("AI_MIN_CONFIDENCE")?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        val entries = filesEnv!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            val (label, path) = if ('=' in entry) {
                entry.substringBefore('=').trim() to entry.substringAfter('=').trim()
            } else {
                File(entry).nameWithoutExtension.removeSuffix("-ai") to entry
            }
            val f = File(path)
            require(f.isFile) { "decision log not found: ${f.absolutePath}" }
            label to f.readText()
        }
        // Don't let two files collapse to the same derived label (associate would silently drop one).
        val dupes = entries.groupBy { it.first }.filter { it.value.size > 1 }.keys
        require(dupes.isEmpty()) { "Duplicate model labels $dupes — disambiguate with explicit label=path in AI_DECISIONS_FILES" }
        val models = entries.toMap()
        assumeTrue(models.isNotEmpty(), "no decision logs resolved from AI_DECISIONS_FILES")

        val config = BacktestRunner.defaultConfig(symbol, equity)
        val table = compareModels(candleFile.readText(), symbol, minConf, config, models).render()

        println(table)
        val out = File(candleFile.absoluteFile.parentFile, "comparison-report.txt")
        out.writeText(table)
        println("Comparison written to: ${out.absolutePath}")
    }
}

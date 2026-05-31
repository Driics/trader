package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal

/**
 * Runnable backtest entrypoint, NOT an assertion of profitability. It is the path of least resistance in
 * this project's gradle-test loop: it runs the [ru.driics.aitrade.domain.strategy.RsiReversionStrategy]
 * over a real OKX candle file and prints + writes the report. Skipped (assumption-aborted) unless the
 * `BACKTEST_FILE` env var points at a JSONL candle file — so a normal `gradlew test` is unaffected.
 *
 * Usage (PowerShell):
 *   $env:BACKTEST_FILE="btc.jsonl"; .\gradlew.bat test --tests '*BacktestHarnessTest*'
 * See docs/backtest-howto.md for how to produce the candle file.
 */
class BacktestHarnessTest {

    @Test
    fun `run RSI reversion over a candle file when BACKTEST_FILE is set`() {
        val path = System.getenv("BACKTEST_FILE")
        assumeTrue(!path.isNullOrBlank(), "set BACKTEST_FILE=<okx-candles.jsonl> to run the backtest harness")

        val file = File(path!!)
        assumeTrue(file.isFile, "BACKTEST_FILE not found: ${file.absolutePath}")

        val symbol = System.getenv("BACKTEST_SYMBOL")?.takeIf { it.isNotBlank() } ?: "BTC-USDT-SWAP"
        val equity = System.getenv("BACKTEST_EQUITY")?.toBigDecimalOrNull() ?: BigDecimal("10000")

        val config = BacktestRunner.defaultConfig(symbol, equity)
        val result = BacktestRunner.run(file.readText(), symbol, config = config)
        val report = BacktestReport.render(equity, result.equityCurve.size, result)

        println(report)
        val out = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".report.txt")
        out.writeText(report)
        println("Report written to: ${out.absolutePath}")

        assertTrue(result.equityCurve.isNotEmpty(), "no bars parsed from $path")
    }
}

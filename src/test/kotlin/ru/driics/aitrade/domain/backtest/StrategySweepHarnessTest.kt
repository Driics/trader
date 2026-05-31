package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.strategy.StrategyCatalog
import java.io.File

/**
 * Runnable RSI parameter sweep over a candle file (offline). Skipped unless `BACKTEST_FILE` is set.
 *   $env:BACKTEST_FILE="data/btc.jsonl"; .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*StrategySweepHarnessTest*'
 */
class StrategySweepHarnessTest {

    @Test
    fun `sweep RSI parameterizations over a candle file when BACKTEST_FILE is set`() {
        val path = System.getenv("BACKTEST_FILE")
        assumeTrue(!path.isNullOrBlank(), "set BACKTEST_FILE to run the strategy sweep")
        val file = File(path!!)
        assumeTrue(file.isFile, "not found: ${file.absolutePath}")
        val symbol = System.getenv("BACKTEST_SYMBOL")?.takeIf { it.isNotBlank() } ?: "BTC-USDT-SWAP"

        val bars = JsonlCandleParser.parse(file.readText())
        val config = BacktestRunner.defaultConfig(symbol)
        val variants = StrategyCatalog.rsiAndDonchianGrid()

        val rows = ParameterSweep.run(symbol, bars, config, variants)
        val table = "bars: ${bars.size}  variants: ${rows.size}\n" + ParameterSweep.renderTable(rows)

        println(table)
        val out = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".sweep.txt")
        out.writeText(table)
        println("Sweep written to: ${out.absolutePath}")

        assertTrue(rows.isNotEmpty())
    }
}

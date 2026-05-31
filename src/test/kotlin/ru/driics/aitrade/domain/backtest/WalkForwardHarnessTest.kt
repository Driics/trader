package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.strategy.StrategyCatalog
import java.io.File

/**
 * Out-of-sample validation of the strategy grid over a candle file (offline). Skipped unless
 * `BACKTEST_FILE` is set. Selecting + judging on the same data is the textbook overfit; this re-selects on
 * a train slice and measures on unseen test data. SLOW — it runs the full grid on each train window
 * (~minutes on a 6-month file); it is a validation step, not a hot path.
 *
 *   $env:BACKTEST_FILE="data/btc.jsonl"; $env:WF_FOLDS="3"
 *   .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*WalkForwardHarnessTest*'
 */
class WalkForwardHarnessTest {

    @Test
    fun `walk-forward validate the strategy grid when BACKTEST_FILE is set`() {
        val path = System.getenv("BACKTEST_FILE")
        assumeTrue(!path.isNullOrBlank(), "set BACKTEST_FILE to run walk-forward validation")
        val file = File(path!!)
        assumeTrue(file.isFile, "not found: ${file.absolutePath}")
        val symbol = System.getenv("BACKTEST_SYMBOL")?.takeIf { it.isNotBlank() } ?: "BTC-USDT-SWAP"
        val folds = System.getenv("WF_FOLDS")?.toIntOrNull() ?: 3

        val bars = JsonlCandleParser.parse(file.readText())
        val config = BacktestRunner.defaultConfig(symbol)
        val variants = StrategyCatalog.rsiAndDonchianGrid()

        val split = WalkForward.splitTest(symbol, bars, config, variants)
        val wf = WalkForward.walkForward(symbol, bars, config, variants, folds)
        val report = "bars: ${bars.size}  variants: ${variants.size}\n" + WalkForward.render(split, wf)

        println(report)
        val out = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".walkforward.txt")
        out.writeText(report)
        println("Walk-forward written to: ${out.absolutePath}")

        assertTrue(wf.isNotEmpty())
    }
}

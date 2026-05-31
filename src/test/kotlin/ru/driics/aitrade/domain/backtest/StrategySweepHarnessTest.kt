package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.strategy.DonchianBreakoutStrategy
import ru.driics.aitrade.domain.strategy.RsiReversionStrategy
import java.io.File
import java.math.BigDecimal

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

        val variants = buildList {
            for ((os, ob) in listOf(20 to 80, 25 to 75, 30 to 70, 35 to 65, 40 to 60)) {
                add("rsi $os/$ob 2:1" to RsiReversionStrategy(BigDecimal(os), BigDecimal(ob), BigDecimal("0.02"), BigDecimal("0.04")))
            }
            for ((s, t, n) in listOf(
                Triple("0.02", "0.02", "1:1"),
                Triple("0.02", "0.06", "3:1"),
                Triple("0.015", "0.045", "3:1 tight"),
                Triple("0.03", "0.045", "1.5:1"),
            )) {
                add("rsi 30/70 sl$s/$t $n" to RsiReversionStrategy(BigDecimal("30"), BigDecimal("70"), BigDecimal(s), BigDecimal(t)))
            }
            // Trend-following contrast: Donchian breakout across channel widths and reward:risk.
            for ((ch, tp, n) in listOf(
                Triple(20, "0.04", "2:1"),
                Triple(20, "0.06", "3:1"),
                Triple(20, "0.10", "5:1"),
                Triple(55, "0.06", "3:1"),
                Triple(10, "0.06", "3:1"),
            )) {
                add("donchian $ch sl0.02/$tp $n" to DonchianBreakoutStrategy(ch, BigDecimal("0.02"), BigDecimal(tp)))
            }
        }

        val rows = ParameterSweep.run(symbol, bars, config, variants)
        val table = "bars: ${bars.size}  variants: ${rows.size}\n" + ParameterSweep.renderTable(rows)

        println(table)
        val out = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".sweep.txt")
        out.writeText(table)
        println("Sweep written to: ${out.absolutePath}")

        assertTrue(rows.isNotEmpty())
    }
}

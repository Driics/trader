package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.engine.BacktestConfig
import ru.driics.aitrade.domain.backtest.engine.InstrumentSpec
import ru.driics.aitrade.domain.backtest.io.compareModels
import java.math.BigDecimal

/**
 * Pins the model-comparison aggregation: several recorded decision logs replayed over the SAME candles,
 * ranked by total return, rendered to a table. The candle series rises monotonically (100 -> 110), so a
 * LONG wins, a SHORT loses, and a log whose timestamps miss the candles trades nothing (0% + a warning).
 */
class ModelComparisonTest {

    private val candles = """
        ["0","100","101","99","100","0"]
        ["60000","100","105","100","104","0"]
        ["120000","104","108","103","107","0"]
        ["180000","107","112","106","110","0"]
        ["240000","110","111","109","110","0"]
    """.trimIndent()

    // BUY at ts 60000 -> fills at open[2]=104, take-profits at 110 -> winner.
    private val upLog =
        """{"timestampMs":60000,"symbol":"X","signal":"buy","stopLoss":100,"takeProfit":110,"leverage":5,"quantity":1,"confidence":0.9}"""

    // SELL at ts 60000 -> fills at 104, price rises into the stop at 110 -> loser.
    private val downLog =
        """{"timestampMs":60000,"symbol":"X","signal":"sell","stopLoss":110,"takeProfit":99,"leverage":5,"quantity":1,"confidence":0.9}"""

    // A decision whose timestamp matches no candle -> all-HOLD -> flat 0% (a file-mismatch, not a choice).
    private val badLog =
        """{"timestampMs":999999,"symbol":"X","signal":"buy","stopLoss":100,"takeProfit":110,"leverage":5,"quantity":1,"confidence":0.9}"""

    private fun config() = BacktestConfig(
        startingEquityUsd = BigDecimal("1000"), takerFeePct = BigDecimal.ZERO,
        marginBufferPct = BigDecimal.ZERO, riskPerTradePct = BigDecimal("0.01"),
        warmupBars = 0, intradayWindow = 1000,
        instruments = mapOf("X" to InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))),
        minLev = 1, maxLev = 40,
    )

    @Test
    fun `compareModels ranks the winner first and the loser last`() {
        val cmp = compareModels(
            candleJsonl = candles, symbol = "X", minConfidence = BigDecimal.ZERO, config = config(),
            models = mapOf("model-up" to upLog, "model-down" to downLog, "model-bad-log" to badLog),
        )

        assertEquals(3, cmp.rows.size)
        assertEquals(5, cmp.bars)
        assertEquals("model-up", cmp.rows.first().label, "highest return must rank #1")
        assertEquals("model-down", cmp.rows.last().label, "the short loser must rank last")
        assertTrue(
            cmp.rows.first().metrics.totalReturnPct > cmp.rows.last().metrics.totalReturnPct,
            "ranking must be by descending total return",
        )
        assertTrue(cmp.rows.first().metrics.totalReturnPct.signum() > 0, "the long must be profitable")
        assertTrue(cmp.rows.last().metrics.totalReturnPct.signum() < 0, "the short must lose")
    }

    @Test
    fun `a decision log that misses every candle is flagged as a non-match`() {
        val cmp = compareModels(
            candleJsonl = candles, symbol = "X", minConfidence = BigDecimal.ZERO, config = config(),
            models = mapOf("model-bad-log" to badLog),
        )
        val row = cmp.rows.single()
        assertEquals(0, row.matched, "no decision lined up with a candle")
        assertEquals(1, row.recorded)
        assertEquals(0, BigDecimal.ZERO.compareTo(row.metrics.totalReturnPct), "no trade -> flat 0%")
    }

    // One decision matches a candle (60000), one does not (999999) -> 1/2 matched -> partial.
    private val partialLog = upLog + "\n" +
        """{"timestampMs":999999,"symbol":"X","signal":"buy","stopLoss":100,"takeProfit":110,"leverage":5,"quantity":1,"confidence":0.9}"""

    @Test
    fun `a partial match is flagged as possibly unrepresentative`() {
        val cmp = compareModels(
            candleJsonl = candles, symbol = "X", minConfidence = BigDecimal.ZERO, config = config(),
            models = mapOf("model-partial" to partialLog),
        )
        val row = cmp.rows.single()
        assertEquals(1, row.matched)
        assertEquals(2, row.recorded)
        assertTrue(
            cmp.render().contains("model-partial: only 1/2 decisions matched"),
            "a partial-match log must be flagged as not fully representative",
        )
    }

    @Test
    fun `render lists models in ranked order and warns on a zero-match log`() {
        val table = compareModels(
            candleJsonl = candles, symbol = "X", minConfidence = BigDecimal.ZERO, config = config(),
            models = mapOf("model-up" to upLog, "model-down" to downLog, "model-bad-log" to badLog),
        ).render()

        // winner appears before loser in the rendered table
        assertTrue(table.indexOf("model-up") < table.indexOf("model-down"), "winner must be listed above loser")
        // the mismatch log gets an explicit warning, not a silent flat row
        assertTrue(
            table.contains("model-bad-log: 0/1 decisions matched"),
            "a zero-match log must be flagged; table was:\n$table",
        )
        assertTrue(table.contains("Model comparison"), "has a header")
    }
}

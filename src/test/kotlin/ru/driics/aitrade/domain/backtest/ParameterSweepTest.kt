package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.strategy.RsiReversionStrategy
import java.math.BigDecimal

class ParameterSweepTest {

    @Test
    fun `runs each variant and renders a sorted comparison table`() {
        val bars = (0 until 60).map { i ->
            val c = BigDecimal(100 + (i % 7) - 3) // gentle oscillation so RSI actually moves
            Bar(i.toLong() * 60_000, c, c + BigDecimal.ONE, c - BigDecimal.ONE, c)
        }
        val config = BacktestRunner.defaultConfig("X").copy(warmupBars = 10, indicatorLookback = 60)
        val variants = listOf(
            "alpha" to RsiReversionStrategy(),
            "beta" to RsiReversionStrategy(BigDecimal("40"), BigDecimal("60")),
        )

        val rows = ParameterSweep.run("X", bars, config, variants)
        assertEquals(2, rows.size)

        val table = ParameterSweep.renderTable(rows)
        assertTrue(table.contains("variant"))
        assertTrue(table.contains("alpha"))
        assertTrue(table.contains("beta"))
    }
}

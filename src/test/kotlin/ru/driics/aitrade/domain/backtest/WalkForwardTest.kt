package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.strategy.DonchianBreakoutStrategy
import ru.driics.aitrade.domain.strategy.RsiReversionStrategy
import java.math.BigDecimal

class WalkForwardTest {

    private fun trendBars(n: Int) = (0 until n).map { i ->
        val c = BigDecimal(1000 + i) // steady uptrend so the variants actually trade
        Bar(i.toLong() * 60_000, c, c + BigDecimal("2"), c - BigDecimal("2"), c)
    }

    private fun config() = BacktestRunner.defaultConfig("X").copy(warmupBars = 5, indicatorLookback = 20)

    private val variants = listOf(
        "donchian10" to DonchianBreakoutStrategy(10, BigDecimal("0.02"), BigDecimal("0.06")),
        "rsi" to RsiReversionStrategy(),
    )

    @Test
    fun `splitTest splits at the train fraction and chooses a grid variant`() {
        val split = WalkForward.splitTest("X", trendBars(200), config(), variants, trainFraction = 0.7)
        assertEquals(140, split.trainBars)
        assertEquals(60, split.testBars)
        assertTrue(split.chosenLabel in setOf("donchian10", "rsi"))
    }

    @Test
    fun `walkForward yields the requested folds with anchored (growing) train windows`() {
        val folds = WalkForward.walkForward("X", trendBars(240), config(), variants, folds = 3)
        assertEquals(listOf(1, 2, 3), folds.map { it.fold })
        // anchored: each fold trains on more history than the last
        assertTrue(folds[0].trainBars < folds[1].trainBars)
        assertTrue(folds[1].trainBars < folds[2].trainBars)
        folds.forEach { assertTrue(it.chosenLabel in setOf("donchian10", "rsi")) }
    }

    @Test
    fun `render reports an out-of-sample verdict and a stability line`() {
        val bars = trendBars(240)
        val cfg = config()
        val text = WalkForward.render(
            WalkForward.splitTest("X", bars, cfg, variants),
            WalkForward.walkForward("X", bars, cfg, variants, folds = 3),
        )
        assertTrue(text.contains("OUT-OF-SAMPLE"))
        assertTrue(text.contains("OOS folds positive:"))
        assertTrue(text.contains("winner stable across folds:"))
    }
}

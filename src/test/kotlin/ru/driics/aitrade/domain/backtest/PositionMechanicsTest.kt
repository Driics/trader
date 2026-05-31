package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.core.Bar
import ru.driics.aitrade.domain.backtest.core.ExitFill
import ru.driics.aitrade.domain.backtest.core.ExitReason
import ru.driics.aitrade.domain.backtest.core.PositionSide
import ru.driics.aitrade.domain.backtest.core.SimPosition
import ru.driics.aitrade.domain.backtest.core.realizedPnlUsd
import ru.driics.aitrade.domain.backtest.core.resolveExit
import java.math.BigDecimal

/**
 * Boundary specs for the simulation's exit resolution and PnL — the heart of fill realism. The
 * pessimistic SL-first rule is what keeps a backtest equity curve honest, so it is pinned hard.
 */
class PositionMechanicsTest {

    private fun bar(o: String, h: String, l: String, c: String) =
        Bar(0L, BigDecimal(o), BigDecimal(h), BigDecimal(l), BigDecimal(c))

    private fun longPos(sl: String?, tp: String?) = SimPosition(
        symbol = "BTC", side = PositionSide.LONG, entryPrice = BigDecimal("100"),
        quantity = BigDecimal("1"), stopLoss = sl?.let { BigDecimal(it) }, takeProfit = tp?.let { BigDecimal(it) },
        leverage = 1, entryTimestampMs = 0L, entryFeeUsd = BigDecimal.ZERO,
    )

    private fun shortPos(sl: String?, tp: String?) = longPos(sl, tp).copy(side = PositionSide.SHORT)

    // ---- LONG ----

    @Test
    fun `long stop fills when low touches the stop (inclusive)`() {
        assertEquals(
            ExitFill(BigDecimal("95"), ExitReason.STOP_LOSS),
            resolveExit(longPos(sl = "95", tp = "110"), bar("100", "105", "95", "96")),
        )
    }

    @Test
    fun `long target fills when high reaches target and stop untouched`() {
        assertEquals(
            ExitFill(BigDecimal("110"), ExitReason.TAKE_PROFIT),
            resolveExit(longPos(sl = "95", tp = "110"), bar("100", "110", "99", "108")),
        )
    }

    @Test
    fun `long both touchable in one bar fills the STOP (pessimistic)`() {
        assertEquals(
            ExitFill(BigDecimal("95"), ExitReason.STOP_LOSS),
            resolveExit(longPos(sl = "95", tp = "110"), bar("100", "112", "94", "108")),
        )
    }

    @Test
    fun `long neither level touched returns null`() {
        assertNull(resolveExit(longPos(sl = "95", tp = "110"), bar("100", "109", "96", "105")))
    }

    // ---- SHORT (mirror) ----

    @Test
    fun `short stop fills when high touches the stop`() {
        assertEquals(
            ExitFill(BigDecimal("105"), ExitReason.STOP_LOSS),
            resolveExit(shortPos(sl = "105", tp = "90"), bar("100", "105", "95", "104")),
        )
    }

    @Test
    fun `short both touchable fills the STOP (pessimistic)`() {
        assertEquals(
            ExitFill(BigDecimal("105"), ExitReason.STOP_LOSS),
            resolveExit(shortPos(sl = "105", tp = "90"), bar("100", "106", "89", "95")),
        )
    }

    @Test
    fun `null levels cannot trigger`() {
        assertNull(resolveExit(longPos(sl = null, tp = null), bar("100", "999", "1", "100")))
    }

    // ---- PnL ----

    @Test
    fun `long pnl is positive when price rises, net of fees`() {
        // (110 - 100) * 2 - 1 fee = 19
        assertEquals(
            0,
            BigDecimal("19").compareTo(
                realizedPnlUsd(PositionSide.LONG, BigDecimal("100"), BigDecimal("110"), BigDecimal("2"), BigDecimal("1")),
            ),
        )
    }

    @Test
    fun `short pnl is positive when price falls, net of fees`() {
        // (100 - 90) * 2 - 1 = 19
        assertEquals(
            0,
            BigDecimal("19").compareTo(
                realizedPnlUsd(PositionSide.SHORT, BigDecimal("100"), BigDecimal("90"), BigDecimal("2"), BigDecimal("1")),
            ),
        )
    }
}

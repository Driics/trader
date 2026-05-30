package ru.driics.aitrade.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Boundary specs for the Phase-3 step-3 entry-price selector. This function picks the price that
 * sizes a real order, so the fallback rules are pinned: a missing WS price OR one that diverges past
 * the sanity delta must yield REST; only a fresh, in-delta WS price is allowed to win.
 */
class EntryPriceSelectionTest {

    private val maxDeltaBps = BigDecimal("50") // 0.5%
    private val rest = BigDecimal("50000")

    @Test
    fun `null ws price falls back to rest`() {
        assertSame(rest, selectEntryPrice(null, rest, maxDeltaBps))
    }

    @Test
    fun `fresh ws within the delta is used`() {
        val ws = BigDecimal("50010") // 2 bps off
        assertSame(ws, selectEntryPrice(ws, rest, maxDeltaBps))
    }

    @Test
    fun `ws beyond the max delta falls back to rest`() {
        val ws = BigDecimal("50500") // 100 bps off > 50
        assertSame(rest, selectEntryPrice(ws, rest, maxDeltaBps))
    }

    @Test
    fun `delta exactly at the max is trusted (inclusive)`() {
        val ws = BigDecimal("50250") // 50 bps off, == max -> not rejected
        assertSame(ws, selectEntryPrice(ws, rest, maxDeltaBps))
    }

    @Test
    fun `one tick past the max is rejected`() {
        val ws = BigDecimal("50255") // 51 bps off > 50
        assertEquals(0, rest.compareTo(selectEntryPrice(ws, rest, maxDeltaBps)))
    }
}

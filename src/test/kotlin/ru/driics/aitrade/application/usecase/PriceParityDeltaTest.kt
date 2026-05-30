package ru.driics.aitrade.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pins the WS-vs-REST parity delta in basis points. This number is the Phase-3 step-2 evidence the
 * step-3 value-swap decision rests on, so the formula |ws - rest| / rest * 10_000 is verified
 * directly rather than trusted by inspection.
 */
class PriceParityDeltaTest {

    private fun bps(ws: String, rest: String): BigDecimal =
        priceDeltaBps(BigDecimal(ws), BigDecimal(rest))

    @Test
    fun `identical prices are zero bps`() {
        assertEquals(0, BigDecimal("0.00").compareTo(bps("50000", "50000")))
    }

    @Test
    fun `one percent above rest is 100 bps`() {
        assertEquals(0, BigDecimal("100.00").compareTo(bps("50500", "50000")))
    }

    @Test
    fun `delta is absolute, so below rest is also positive`() {
        assertEquals(0, BigDecimal("100.00").compareTo(bps("49500", "50000")))
    }

    @Test
    fun `fifty on fifty-thousand is ten bps`() {
        assertEquals(0, BigDecimal("10.00").compareTo(bps("50050", "50000")))
    }

    @Test
    fun `zero rest price yields zero, never divide-by-zero`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(bps("50000", "0")))
    }
}

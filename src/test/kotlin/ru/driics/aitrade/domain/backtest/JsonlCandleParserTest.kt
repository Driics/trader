package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class JsonlCandleParserTest {

    @Test
    fun `parses OKX candle arrays and sorts ascending by timestamp`() {
        // OKX returns newest-first; the parser must hand the engine ascending bars.
        val jsonl = """
            ["120000","104","108","103","107","10","x","y","1"]
            ["60000","100","105","100","104","20","x","y","1"]
        """.trimIndent()

        val bars = JsonlCandleParser.parse(jsonl)

        assertEquals(2, bars.size)
        assertEquals(60_000, bars[0].timestampMs)
        assertEquals(0, BigDecimal("104").compareTo(bars[0].close))
        assertEquals(0, BigDecimal("20").compareTo(bars[0].volume))
        assertEquals(120_000, bars[1].timestampMs)
        assertEquals(0, BigDecimal("108").compareTo(bars[1].high))
        assertEquals(0, BigDecimal("103").compareTo(bars[1].low))
    }

    @Test
    fun `skips blank and comment lines`() {
        val jsonl = """
            # candles for BTC-USDT-SWAP

            ["60000","100","105","100","104","20"]

            # end
        """.trimIndent()

        val bars = JsonlCandleParser.parse(jsonl)
        assertEquals(1, bars.size)
        assertEquals(60_000, bars[0].timestampMs)
    }

    @Test
    fun `tolerates a short array without volume`() {
        val bars = JsonlCandleParser.parse("""["60000","100","105","100","104"]""")
        assertEquals(1, bars.size)
        assertEquals(0, BigDecimal.ZERO.compareTo(bars[0].volume))
    }

    @Test
    fun `a too-short array is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonlCandleParser.parse("""["60000","100","105"]""")
        }
    }

    @Test
    fun `malformed json is rejected`() {
        assertThrows(Exception::class.java) {
            JsonlCandleParser.parse("this is not json {")
        }
    }
}

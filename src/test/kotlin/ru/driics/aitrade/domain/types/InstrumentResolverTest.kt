package ru.driics.aitrade.domain.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The instId build/parse rules that let the app trade any pair from config alone. These pin the OKX
 * format so the config-driven path can't silently drift from "BASE-QUOTE-SWAP".
 */
class InstrumentResolverTest {

    @Test
    fun `builds a USDT-SWAP instId for the default perp config`() {
        val resolver = InstrumentResolver("USDT", "SWAP")
        assertEquals("BTC-USDT-SWAP", resolver.instrumentId("BTC").value)
        assertEquals("ETH-USDT-SWAP", resolver.instrumentId(Symbol.from("eth")).value)
    }

    @Test
    fun `honors a different quote currency without code changes`() {
        assertEquals("BTC-USDC-SWAP", InstrumentResolver("USDC", "SWAP").instrumentId("BTC").value)
    }

    @Test
    fun `SPOT omits the type segment per OKX format`() {
        assertEquals("BTC-USDT", InstrumentResolver("USDT", "SPOT").instrumentId("BTC").value)
    }

    @Test
    fun `instId round-trips back to the base symbol`() {
        val instId = InstrumentResolver("USDT", "SWAP").instrumentId("SOL")
        assertEquals(Symbol.from("SOL"), instId.toSymbol())
    }

    @Test
    fun `lowercase config is normalized to uppercase`() {
        assertEquals("BTC-USDT-SWAP", InstrumentResolver("usdt", "swap").instrumentId("btc").value)
    }

    @Test
    fun `blank quote currency is rejected`() {
        assertThrows<IllegalArgumentException> {
            InstrumentResolver(" ", "SWAP").instrumentId("BTC")
        }
    }
}

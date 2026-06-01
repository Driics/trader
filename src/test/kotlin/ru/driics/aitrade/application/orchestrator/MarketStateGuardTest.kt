package ru.driics.aitrade.application.orchestrator

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

/**
 * Pins the empty-data guard that skips the AI call when a cycle's whole market snapshot is unusable
 * (every per-symbol fetch failed/timed out -> all prices 0). Prevents an empty prompt -> "{}" -> a
 * misleading schema rejection (and the wasted AI spend).
 */
class MarketStateGuardTest {

    private fun ccy(symbol: String, price: String) = CurrencyMarketData(
        symbol = symbol, currentPrice = BigDecimal(price),
        currentEma20 = BigDecimal.ZERO, currentMacd = BigDecimal.ZERO, currentRsi7 = BigDecimal.ZERO,
    )

    private fun state(vararg ccys: CurrencyMarketData) = MarketState(
        timestamp = 0L, minutesSinceStart = 0L, invocationCount = 1L,
        currencies = ccys.associateBy { it.symbol },
        account = AccountInfo(totalReturn = BigDecimal.ZERO, availableCash = BigDecimal.ZERO, accountValue = BigDecimal.ZERO),
        positions = emptyList(),
    )

    @Test
    fun `no currencies at all is treated as no usable data`() {
        assertTrue(state().hasNoUsableMarketData())
    }

    @Test
    fun `every currency at price zero (all fetches failed) is no usable data`() {
        assertTrue(state(ccy("BTC", "0"), ccy("ETH", "0")).hasNoUsableMarketData())
    }

    @Test
    fun `a single currency with a real price means there IS usable data`() {
        // Partial fetch failure (BTC empty, ETH ok) must NOT skip — the AI can still act on ETH.
        assertFalse(state(ccy("BTC", "0"), ccy("ETH", "2500")).hasNoUsableMarketData())
    }

    @Test
    fun `all currencies priced means usable data`() {
        assertFalse(state(ccy("BTC", "65000"), ccy("ETH", "2500")).hasNoUsableMarketData())
    }
}

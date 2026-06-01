package ru.driics.aitrade.domain.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

/**
 * Pins that the prompt's market-data section omits symbols whose fetch FAILED (the empty placeholder with
 * price 0 — see OkxExchangeAdapter.emptyCurrencyData), so on a partial fetch failure the model is never fed
 * all-zero price/indicators for a coin we have no data on.
 */
class PromptBuilderTest {

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
    fun `market-data section omits zero-price (failed-fetch) symbols but keeps real ones`() {
        val section = PromptBuilder.buildMarketDataSection(state(ccy("BTC", "0"), ccy("ETH", "2500")))

        assertFalse(section.contains("ALL BTC DATA"), "a failed-fetch (price 0) symbol must be omitted")
        assertTrue(section.contains("ALL ETH DATA"), "a symbol with real data must be present")
    }

    @Test
    fun `market-data section keeps all symbols when every fetch succeeded`() {
        val section = PromptBuilder.buildMarketDataSection(state(ccy("BTC", "65000"), ccy("ETH", "2500")))

        assertTrue(section.contains("ALL BTC DATA"))
        assertTrue(section.contains("ALL ETH DATA"))
    }
}

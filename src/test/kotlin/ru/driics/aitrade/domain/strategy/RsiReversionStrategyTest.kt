package ru.driics.aitrade.domain.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

class RsiReversionStrategyTest {

    private fun stateWithRsi(rsi: String, price: String = "100") = MarketState(
        timestamp = 0, minutesSinceStart = 0, invocationCount = 0,
        currencies = mapOf(
            "BTC" to CurrencyMarketData(
                symbol = "BTC",
                currentPrice = BigDecimal(price),
                currentEma20 = BigDecimal.ZERO,
                currentMacd = BigDecimal.ZERO,
                currentRsi7 = BigDecimal(rsi),
            ),
        ),
        account = AccountInfo(BigDecimal.ZERO, BigDecimal("1000"), BigDecimal("1000")),
        positions = emptyList(),
    )

    private val strategy = RsiReversionStrategy()

    @Test
    fun `oversold goes long with stop below and target above`() {
        val d = strategy.decide(stateWithRsi("25")).single()
        assertEquals(AiSignal.BUY, d.signal)
        assertEquals(0, BigDecimal("98").compareTo(d.stopLoss!!))     // 100 * (1 - 0.02)
        assertEquals(0, BigDecimal("104").compareTo(d.takeProfit!!))  // 100 * (1 + 0.04)
    }

    @Test
    fun `overbought goes short with stop above and target below`() {
        val d = strategy.decide(stateWithRsi("75")).single()
        assertEquals(AiSignal.SELL, d.signal)
        assertEquals(0, BigDecimal("102").compareTo(d.stopLoss!!))
        assertEquals(0, BigDecimal("96").compareTo(d.takeProfit!!))
    }

    @Test
    fun `neutral rsi holds`() {
        assertEquals(AiSignal.HOLD, strategy.decide(stateWithRsi("50")).single().signal)
    }

    @Test
    fun `oversold boundary value triggers long (inclusive)`() {
        assertEquals(AiSignal.BUY, strategy.decide(stateWithRsi("30")).single().signal)
    }

    @Test
    fun `non-positive price is skipped`() {
        assertEquals(emptyList<StrategyDecision>(), strategy.decide(stateWithRsi("25", price = "0")))
    }
}

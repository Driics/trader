package ru.driics.aitrade.application.ai

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

class TemplatedPromptRendererTest {

    private val templateService = mockk<PromptTemplateService>()
    private val props = TradingProperties(currencies = listOf("BTC")) // minConfidence 0.60, maxLev 40, minLev 5
    private val renderer = TemplatedPromptRenderer(templateService, props)

    private val state = MarketState(
        timestamp = 0, minutesSinceStart = 0, invocationCount = 0,
        currencies = mapOf(
            "BTC" to CurrencyMarketData("BTC", BigDecimal("50000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("55")),
        ),
        account = AccountInfo(BigDecimal.ZERO, BigDecimal("1234"), BigDecimal("1234")),
        positions = emptyList(),
    )

    @Test
    fun `passes the market data section and trading-properties constraints into the template`() {
        // Echo each template variable back so we can assert exactly what the renderer supplied.
        every {
            templateService.renderUserPrompt(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            listOf(
                "marketData=${firstArg<String>()}",
                "minutesSinceStart=${arg<Long>(2)}",
                "invocationCount=${arg<Long>(3)}",
                "maxLeverage=${arg<Int>(4)}",
                "minLeverage=${arg<Int>(5)}",
                "minConfidence=${arg<String>(6)}",
                "availableCashUsd=${arg<String>(7)}",
            ).joinToString("|")
        }

        val out = renderer.render(state, minutesSinceStart = 42, invocationCount = 7)

        assertTrue(out.contains("current_price"), out) // PromptBuilder market-data section was injected
        assertTrue(out.contains("BTC"), out)
        assertTrue(out.contains("minutesSinceStart=42"), out)
        assertTrue(out.contains("invocationCount=7"), out)
        assertTrue(out.contains("maxLeverage=40"), out)
        assertTrue(out.contains("minLeverage=5"), out)
        assertTrue(out.contains("minConfidence=0.60"), out) // setScale(2, HALF_UP)
    }
}

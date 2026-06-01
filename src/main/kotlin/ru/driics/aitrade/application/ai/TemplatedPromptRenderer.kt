package ru.driics.aitrade.application.ai

import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.services.PromptBuilder
import ru.driics.aitrade.domain.services.PromptFormatter
import java.math.RoundingMode

/**
 * Renders the templated user prompt from a [MarketState] — the single source of truth for how a market
 * snapshot becomes the prompt sent to the LLM. Extracted from BuildPromptUseCase so the live decision loop
 * AND the offline backtest recorder build the prompt identically (the recorder previously used only the raw
 * PromptBuilder sections, missing the template's schema/constraint instructions).
 *
 * [minutesSinceStart] is supplied by the caller (live uses wall-clock since session start; the recorder
 * uses the bar window) so this renderer stays free of any clock/session concern.
 */
class TemplatedPromptRenderer(
    private val templateService: PromptTemplateService,
    private val tradingProperties: TradingProperties,
) {
    fun render(marketState: MarketState, minutesSinceStart: Long, invocationCount: Long): String {
        val marketDataSection = PromptBuilder.buildMarketDataSection(marketState)
        val accountInfoSection = PromptBuilder.buildAccountInfoSection(marketState)
        val availableCashUsd = PromptFormatter.formatMoneyUsd(marketState.account.availableCash)
        val minConfidence = "${tradingProperties.minConfidence.setScale(2, RoundingMode.HALF_UP)}"

        return templateService.renderUserPrompt(
            marketData = marketDataSection,
            accountInfo = accountInfoSection,
            minutesSinceStart = minutesSinceStart,
            invocationCount = invocationCount,
            maxLeverage = tradingProperties.maxLeverage,
            minLeverage = tradingProperties.minLeverage,
            minConfidence = minConfidence,
            availableCashUsd = availableCashUsd,
        )
    }
}

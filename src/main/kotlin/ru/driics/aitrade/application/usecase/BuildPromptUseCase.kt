package ru.driics.aitrade.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.application.ai.PromptTemplateService
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PromptOutputPort
import ru.driics.aitrade.domain.services.PromptBuilder
import ru.driics.aitrade.domain.services.PromptFormatter
import ru.driics.aitrade.domain.types.asSymbol
import java.time.Clock
import java.util.concurrent.TimeUnit

class BuildPromptUseCase(
    private val market: MarketDataPort,
    private val outputPort: PromptOutputPort,
    private val templateService: PromptTemplateService,
    private val tradingProperties: TradingProperties,
    private val clock: Clock
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    suspend fun execute(
        symbols: List<String>,
        sessionStartMs: Long,
        invocation: Long
    ): String {
        log.info { "Building prompt for ${symbols.size} symbols (invocation #$invocation)" }

        val state = market.loadMarketState(symbols.map { it.asSymbol() })
        
        // Use template-based prompt if available, fallback to legacy builder
        val prompt = try {
            buildTemplatePrompt(state, sessionStartMs, invocation)
        } catch (e: Exception) {
            log.warn(e) { "Failed to build template prompt, falling back to legacy builder" }
            PromptBuilder.build(state, sessionStartMs, invocation, clock)
        }

        val written = outputPort.write(prompt)
        if (written) {
            log.debug { "Prompt written to output (${prompt.length} chars, version: ${templateService.version})" }
        } else {
            log.warn { "Failed to write prompt to output" }
        }

        return prompt
    }

    private fun buildTemplatePrompt(
        marketState: ru.driics.aitrade.domain.model.MarketState,
        sessionStartMs: Long,
        invocation: Long
    ): String {
        val minutesSinceStart = TimeUnit.MILLISECONDS.toMinutes(
            clock.instant().toEpochMilli() - sessionStartMs
        )

        val marketDataSection = PromptBuilder.buildMarketDataSection(marketState)
        val accountInfoSection = PromptBuilder.buildAccountInfoSection(marketState)

        val availableCashUsd = PromptFormatter.formatMoneyUsd(marketState.account.availableCash)
        val minConfidence = "${tradingProperties.minConfidence.setScale(2, java.math.RoundingMode.HALF_UP)}"

        return templateService.renderUserPrompt(
            marketData = marketDataSection,
            accountInfo = accountInfoSection,
            minutesSinceStart = minutesSinceStart,
            invocationCount = invocation,
            maxLeverage = tradingProperties.maxLeverage,
            minLeverage = tradingProperties.minLeverage,
            minConfidence = minConfidence,
            availableCashUsd = availableCashUsd
        )
    }
}
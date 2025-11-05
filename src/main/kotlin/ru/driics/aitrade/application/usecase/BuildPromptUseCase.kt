package ru.driics.aitrade.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PromptOutputPort
import ru.driics.aitrade.domain.services.PromptBuilder
import ru.driics.aitrade.model.MarketState

class BuildPromptUseCase(
    private val market: MarketDataPort,
    private val outputPort: PromptOutputPort
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

        val state = market.loadMarketState(symbols)
        val prompt = PromptBuilder.build(state, sessionStartMs, invocation)

        val written = outputPort.write(prompt)
        if (written) {
            log.debug { "Prompt written to output (${prompt.length} chars)" }
        } else {
            log.warn { "Failed to write prompt to output" }
        }

        return prompt
    }
}
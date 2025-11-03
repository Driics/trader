package ru.driics.aitrade.application.usecase

import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PromptOutputPort
import ru.driics.aitrade.model.MarketState
import ru.driics.aitrade.service.PromptBuilderService

class BuildPromptUseCase(
    private val market: MarketDataPort,
    private val builder: PromptBuilderService,
    private val outputPort: PromptOutputPort
) {
    suspend fun execute(
        symbols: List<String>,
        sessionStartMs: Long,
        invocation: Long
    ): String {
        val state: MarketState = market.loadMarketState(symbols)
        val prompt = builder.buildPrompt(state, sessionStartMs, invocation)
        outputPort.write(prompt)
        outputPort.print(prompt)
        return prompt
    }
}
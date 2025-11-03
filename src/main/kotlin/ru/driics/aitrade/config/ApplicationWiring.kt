package ru.driics.aitrade.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import ru.driics.aitrade.domain.ports.AiAnalysisPort
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PromptOutputPort
import ru.driics.aitrade.service.AiTradeExecutionService
import ru.driics.aitrade.service.PromptBuilderService

@Configuration
class ApplicationWiring(
    private val tradingProperties: TradingProperties
) {
    @Bean
    fun buildPromptUseCase(
        market: MarketDataPort,
        builder: PromptBuilderService,
        out: PromptOutputPort
    ) = BuildPromptUseCase(market, builder, out)

    @Bean
    fun analyzePromptUseCase(ai: AiAnalysisPort) = AnalyzePromptUseCase(ai)

    @Bean
    fun executeAiUseCase(legacy: AiTradeExecutionService) = ExecuteAiDecisionsUseCase(legacy)

    @Bean
    fun updateCycleOrchestrator(
        build: BuildPromptUseCase,
        analyze: AnalyzePromptUseCase,
        execute: ExecuteAiDecisionsUseCase
    ) = UpdateCycleOrchestrator(
        build = build,
        analyze = analyze,
        execute = execute,
        autoExecute = tradingProperties.autoExecute,
        symbols = tradingProperties.getCurrenciesList(),
        sessionStart = { System.currentTimeMillis() - 0L }, // PromptBuilderService already prints minutes; replace with real start if stored
        invocation = { 0L } // you can wire OkxMarketDataService.getInvocationCount if needed
    )
}
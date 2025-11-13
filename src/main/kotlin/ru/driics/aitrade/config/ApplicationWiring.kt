package ru.driics.aitrade.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import ru.driics.aitrade.domain.ports.AiAnalysisPort
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PromptOutputPort
import ru.driics.aitrade.domain.ports.TradingPort
import java.time.Clock

@Configuration
class ApplicationWiring(
    private val tradingProperties: TradingProperties
) {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun buildPromptUseCase(
        market: MarketDataPort,
        out: PromptOutputPort
    ) = BuildPromptUseCase(market, out)

    @Bean
    fun analyzePromptUseCase(ai: AiAnalysisPort) = AnalyzePromptUseCase(ai)

    @Bean
    fun executeAiUseCase(
        trading: TradingPort,
        market: MarketDataPort
    ) = ExecuteAiDecisionsUseCase(trading, market, tradingProperties)

    @Bean
    fun updateCycleOrchestrator(
        build: BuildPromptUseCase,
        analyze: AnalyzePromptUseCase,
        execute: ExecuteAiDecisionsUseCase,
        market: MarketDataPort,
        meterRegistry: MeterRegistry
    ) = UpdateCycleOrchestrator(
        build = build,
        analyze = analyze,
        execute = execute,
        market = market,
        meterRegistry = meterRegistry,
        autoExecute = tradingProperties.autoExecute,
        symbols = tradingProperties.getCurrenciesList(),
        clock()
    )
}
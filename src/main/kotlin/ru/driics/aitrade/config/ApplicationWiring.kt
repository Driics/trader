package ru.driics.aitrade.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Validator
import org.springframework.core.io.ResourceLoader
import ru.driics.aitrade.application.ai.AiBudgetLimiter
import ru.driics.aitrade.application.ai.AiSchemaValidator
import ru.driics.aitrade.application.ai.PromptTemplateService
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
    fun promptTemplateService(
        resourceLoader: ResourceLoader,
        clock: Clock
    ) = PromptTemplateService(
        resourceLoader = resourceLoader,
        clock = clock,
        version = "v1" // Can be made configurable via TradingProperties
    )

    @Bean
    fun buildPromptUseCase(
        market: MarketDataPort,
        out: PromptOutputPort,
        templateService: PromptTemplateService,
        clock: Clock
    ) = BuildPromptUseCase(
        market = market,
        outputPort = out,
        templateService = templateService,
        tradingProperties = tradingProperties,
        clock = clock
    )

    @Bean
    fun aiBudgetLimiter(
        clock: Clock,
        meterRegistry: MeterRegistry
    ) = AiBudgetLimiter(
        budgetPerMinute = tradingProperties.aiBudgetPerMinute,
        clock = clock,
        meterRegistry = meterRegistry
    )

    @Bean
    fun analyzePromptUseCase(
        ai: AiAnalysisPort,
        budgetLimiter: AiBudgetLimiter,
        meterRegistry: MeterRegistry
    ) = AnalyzePromptUseCase(
        ai = ai,
        tradingProperties = tradingProperties,
        budgetLimiter = budgetLimiter,
        meterRegistry = meterRegistry
    )

    @Bean
    fun executeAiUseCase(
        trading: TradingPort,
        market: MarketDataPort,
        clock: Clock
    ) = ExecuteAiDecisionsUseCase(trading, market, tradingProperties, clock)

    @Bean
    fun aiSchemaValidator(
        objectMapper: ObjectMapper,
        validator: Validator
    ) = AiSchemaValidator(objectMapper, validator)

    @Bean
    fun updateCycleOrchestrator(
        build: BuildPromptUseCase,
        analyze: AnalyzePromptUseCase,
        execute: ExecuteAiDecisionsUseCase,
        market: MarketDataPort,
        meterRegistry: MeterRegistry,
        schemaValidator: AiSchemaValidator
    ) = UpdateCycleOrchestrator(
        build = build,
        analyze = analyze,
        execute = execute,
        market = market,
        meterRegistry = meterRegistry,
        autoExecute = tradingProperties.autoExecute,
        symbols = tradingProperties.getCurrenciesList(),
        clock(),
        schemaValidator,
        tradingProperties
    )
}
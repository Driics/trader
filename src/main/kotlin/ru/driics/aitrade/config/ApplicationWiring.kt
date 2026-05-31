package ru.driics.aitrade.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Validator
import org.springframework.core.io.ResourceLoader
import ru.driics.aitrade.application.ai.AiBudgetLimiter
import ru.driics.aitrade.application.ai.AiSchemaValidator
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.ai.PromptTemplateService
import ru.driics.aitrade.application.ai.TemplatedPromptRenderer
import ru.driics.aitrade.application.journal.TradeCloseCollector
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.application.risk.KillSwitchState
import ru.driics.aitrade.application.risk.RiskGate
import ru.driics.aitrade.domain.ports.KillSwitchStore
import ru.driics.aitrade.infra.risk.FileKillSwitchStore
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import io.opentelemetry.api.trace.Tracer
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.AiAnalysisPort
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PromptOutputPort
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.ApiKeyRotationPolicy
import ru.driics.aitrade.domain.services.TradingMetricsService
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.infra.ai.RotatingOpenRouterClient
import ru.driics.aitrade.infra.recording.JsonlDecisionLogSink
import java.nio.file.Path
import java.time.Clock

@Configuration
class ApplicationWiring(
    private val tradingProperties: TradingProperties
) {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun rotatingOpenRouterClient(openRouterProperties: OpenRouterProperties): RotatingOpenRouterClient {
        val apiKeys = openRouterProperties.getApiKeysList()
        require(apiKeys.isNotEmpty()) { "At least one OpenRouter API key must be configured" }
        return RotatingOpenRouterClient(
            rotationPolicy = ApiKeyRotationPolicy(apiKeys),
            maxRetries = openRouterProperties.maxRetries,
            retryDelayMs = openRouterProperties.retryDelayMs,
        )
    }

    @Bean
    fun killSwitchStore(riskGateProperties: RiskGateProperties): KillSwitchStore =
        FileKillSwitchStore(java.nio.file.Path.of(riskGateProperties.killSwitchFile))

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
    fun templatedPromptRenderer(
        templateService: PromptTemplateService
    ) = TemplatedPromptRenderer(
        templateService = templateService,
        tradingProperties = tradingProperties
    )

    @Bean
    fun buildPromptUseCase(
        market: MarketDataPort,
        out: PromptOutputPort,
        templateService: PromptTemplateService,
        promptRenderer: TemplatedPromptRenderer,
        clock: Clock
    ) = BuildPromptUseCase(
        market = market,
        outputPort = out,
        templateService = templateService,
        promptRenderer = promptRenderer,
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
    fun confidenceCalibrator(
        clock: Clock
    ) = ConfidenceCalibrator(
        tradingProperties = tradingProperties,
        clock = clock
    )

    @Bean
    fun riskGate(
        riskGateProperties: RiskGateProperties,
        killSwitchState: KillSwitchState,
        meterRegistry: MeterRegistry,
    ) = RiskGate(
        props = riskGateProperties,
        killSwitch = killSwitchState,
        meterRegistry = meterRegistry,
    )

    @Bean
    fun performanceAnalytics() = ru.driics.aitrade.domain.analytics.PerformanceAnalytics()

    @Bean
    fun instrumentResolver() = InstrumentResolver(
        quoteCurrency = tradingProperties.quoteCurrency,
        instrumentType = tradingProperties.instrumentType,
    )

    @Bean
    fun executeAiUseCase(
        trading: TradingPort,
        clock: Clock,
        confidenceCalibrator: ConfidenceCalibrator,
        meterRegistry: MeterRegistry,
        riskGate: RiskGate,
        riskGateProperties: RiskGateProperties,
        streaming: StreamingMarketDataPort,
        instrumentResolver: InstrumentResolver,
        tradeJournal: TradeJournalPort,
        okxProperties: OkxProperties,
    ) = ExecuteAiDecisionsUseCase(
        trading = trading,
        tradingProperties = tradingProperties,
        clock = clock,
        confidenceCalibrator = confidenceCalibrator,
        meterRegistry = meterRegistry,
        riskGate = riskGate,
        riskGateProperties = riskGateProperties,
        streaming = streaming,
        instrumentResolver = instrumentResolver,
        tradeJournal = tradeJournal,
        tradingMode = TradingMode.resolve(tradingProperties.demoMode, okxProperties.paper),
    )

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
        trading: TradingPort,
        killSwitchState: KillSwitchState,
        riskGateProperties: RiskGateProperties,
        meterRegistry: MeterRegistry,
        tradingMetricsService: TradingMetricsService,
        schemaValidator: AiSchemaValidator,
        confidenceCalibrator: ConfidenceCalibrator,
        tracer: Tracer,
        clock: Clock,
        tradeJournal: TradeJournalPort,
        tradeCloseCollector: TradeCloseCollector? = null,
    ) = UpdateCycleOrchestrator(
        config = UpdateCycleOrchestrator.OrchestratorConfig(
            symbols = tradingProperties.getCurrenciesList(),
            autoExecute = tradingProperties.autoExecute
        ),
        useCases = UpdateCycleOrchestrator.UseCases(
            build = build,
            analyze = analyze,
            execute = execute
        ),
        infrastructure = UpdateCycleOrchestrator.Infrastructure(
            market = market,
            trading = trading,
            killSwitchState = killSwitchState,
            riskGateProperties = riskGateProperties,
            meterRegistry = meterRegistry,
            tradingMetrics = tradingMetricsService,
            schemaValidator = schemaValidator,
            confidenceCalibrator = confidenceCalibrator,
            tracer = tracer,
            clock = clock,
            tradeJournal = tradeJournal,
            decisionLogSink = tradingProperties.decisionLogFile?.let { JsonlDecisionLogSink(Path.of(it)) },
            tradeCloseCollector = tradeCloseCollector,
        )
    )
}

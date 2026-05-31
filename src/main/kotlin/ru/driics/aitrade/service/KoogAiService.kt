package ru.driics.aitrade.service

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.driics.aitrade.common.measureSuspend
import ru.driics.aitrade.config.OpenRouterProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiAnalysisResponse
import ru.driics.aitrade.domain.model.AiService
import ru.driics.aitrade.domain.model.LastAiAnalysis
import ru.driics.aitrade.domain.services.ApiKeyRotationPolicy
import ru.driics.aitrade.infra.ai.RotatingOpenRouterClient
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.measureTimedValue

@Service
class KoogAiService(
    openRouterProperties: OpenRouterProperties,
    private val meterRegistry: MeterRegistry,
    private val tradingProperties: TradingProperties,
    @Value("\${ai.custom.system-prompt:You are an expert crypto trading analyst.}")
    private val systemPrompt: String
) : AiService {

    private companion object {
        val log = KotlinLogging.logger {}

        const val PROVIDER_NAME = "koog-openrouter"
        const val PROMPT_ID = "signal-gen"
        const val METRIC_NAME = "ai.analyze"
        const val CONTEXT_LENGTH = 131_072L
    }

    private val rotatingClient: RotatingOpenRouterClient
    private val lastAnalysis = AtomicReference<LastAiAnalysis?>(null)

    // Define model configuration once
    private val llmModel by lazy {
        LLModel(
            provider = LLMProvider.OpenRouter,
            id = tradingProperties.aiModel,
            contextLength = CONTEXT_LENGTH,
            capabilities = listOf(LLMCapability.Temperature, LLMCapability.Completion)
        )
    }

    init {
        val apiKeys = openRouterProperties.getApiKeysList()
        require(apiKeys.isNotEmpty()) { "At least one OpenRouter API key must be configured" }

        val policy = ApiKeyRotationPolicy(apiKeys)
        rotatingClient = RotatingOpenRouterClient(
            rotationPolicy = policy,
            maxRetries = openRouterProperties.maxRetries,
            retryDelayMs = openRouterProperties.retryDelayMs
        )

        log.info { "Initialized KoogAiService with ${apiKeys.size} keys. Model: ${tradingProperties.aiModel}" }
    }

    override fun getProviderName(): String = PROVIDER_NAME

    override fun getModel(): String = tradingProperties.aiModel

    override fun getLastAnalysis(): LastAiAnalysis? = lastAnalysis.get()

    override suspend fun analyzePrompt(prompt: String): AiAnalysisResponse {
        // 1. Prepare Request
        val promptRequest = prompt(id = PROMPT_ID, params = LLMParams(temperature = tradingProperties.aiTemperature)) {
            system(systemPrompt)
            user(prompt)
        }

        val modelId = tradingProperties.aiModel

        // 2. Execute with Timing & Error Handling
        // measureTimedValue is idiomatic Kotlin for capturing duration + result
        val (result, duration) = measureTimedValue {
            runCatching {
                // Use the Micrometer extension for metrics recording
                // passing 'this' as TimerScope to potentially set dynamic tags if needed
                meterRegistry.measureSuspend(
                    metricName = METRIC_NAME,
                    staticTags = arrayOf("provider", "koog", "model", modelId)
                ) {
                    rotatingClient.execute(promptRequest, llmModel)
                }
            }
        }

        val durationMs = duration.inWholeMilliseconds
        val timestamp = Instant.now().toEpochMilli()

        // 3. Process Result (Success or Failure)
        return result.fold(
            onSuccess = { llmResponse ->
                val content = llmResponse.content
                val stats = rotatingClient.getRotationStats()

                log.info { "AI call success: ${durationMs}ms | keys=${stats.totalKeys} | mode=${stats.mode} | idx=${stats.currentIndex}" }

                val snapshot = LastAiAnalysis(
                    provider = PROVIDER_NAME,
                    model = modelId,
                    timestamp = timestamp,
                    executionTimeMs = durationMs,
                    success = true,
                    response = content,
                    error = null
                )
                lastAnalysis.set(snapshot)

                AiAnalysisResponse(
                    provider = PROVIDER_NAME,
                    model = modelId,
                    response = content,
                    executionTimeMs = durationMs,
                    isSuccess = true
                )
            },
            onFailure = { ex ->
                log.error(ex) { "Koog/OpenRouter analysis failed after retries" }

                val snapshot = LastAiAnalysis(
                    provider = PROVIDER_NAME,
                    model = modelId,
                    timestamp = timestamp,
                    executionTimeMs = durationMs,
                    success = false,
                    response = null,
                    error = ex.message
                )
                lastAnalysis.set(snapshot)

                AiAnalysisResponse(
                    provider = PROVIDER_NAME,
                    model = modelId,
                    response = "",
                    executionTimeMs = durationMs,
                    isSuccess = false,
                    errorMessage = ex.message
                )
            }
        )
    }

    fun getKeyMode(): RotatingOpenRouterClient.KeyMode = rotatingClient.getMode()

    fun getRotationStats() = rotatingClient.getRotationStats()
}
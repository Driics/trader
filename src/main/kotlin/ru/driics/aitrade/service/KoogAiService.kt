package ru.driics.aitrade.service

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.driics.aitrade.common.timedSuspend
import ru.driics.aitrade.config.OpenRouterProperties
import ru.driics.aitrade.domain.services.ApiKeyRotationPolicy
import ru.driics.aitrade.infra.ai.RotatingOpenRouterClient
import ru.driics.aitrade.model.AiAnalysisResponse
import ru.driics.aitrade.model.AiService
import ru.driics.aitrade.model.LastAiAnalysis
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Service
class KoogAiService(
    private val openRouterProperties: OpenRouterProperties,
    private val meterRegistry: MeterRegistry,
    @param:Value("\${ai.custom.system-prompt:You are an expert crypto trading analyst.}")
    private val systemPrompt: String
) : AiService {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val rotationPolicy: ApiKeyRotationPolicy
    private val rotatingClient: RotatingOpenRouterClient

    init {
        val apiKeys = openRouterProperties.getApiKeysList()
        require(apiKeys.isNotEmpty()) { "At least one OpenRouter API key must be configured" }

        rotationPolicy = ApiKeyRotationPolicy(apiKeys)
        rotatingClient = RotatingOpenRouterClient(
            rotationPolicy = rotationPolicy,
            maxRetries = openRouterProperties.maxRetries,
            retryDelayMs = openRouterProperties.retryDelayMs
        )

        logger.info { "Initialized KoogAiService with ${apiKeys.size} API key(s)" }
    }

    val model = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "qwen/qwen3-max",
        contextLength = 131_072,
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Completion
        )
    )

    private val last = AtomicReference<LastAiAnalysis?>(null)

    override fun getProviderName(): String = "koog-openrouter"

    override fun getModel(): String = model.id

    override fun getLastAnalysis(): LastAiAnalysis? = last.get()

    override suspend fun analyzePrompt(prompt: String): AiAnalysisResponse {
        return try {
            val p = prompt(id = "signal-gen") {
                system(systemPrompt)
                user(prompt)
            }

            val (response, took) = meterRegistry.timedSuspend(
                "ai.analyze",
                "provider", "koog",
                "model", model.id
            ) {
                rotatingClient.execute(p, model)
            }

            val responseText = response.content
            val stats = rotatingClient.getRotationStats()

            logger.info { "AI call ok: ${took}ms | keys=${stats.totalKeys} | mode=${stats.mode} | idx=${stats.currentIndex}" }

            val snapshot = LastAiAnalysis(
                provider = getProviderName(),
                model = model.id,
                timestamp = Instant.now().toEpochMilli(),
                executionTimeMs = took,
                success = true,
                response = responseText,
                error = null
            )
            last.set(snapshot)

            AiAnalysisResponse(
                provider = snapshot.provider,
                model = snapshot.model,
                response = responseText,
                executionTimeMs = took,
                isSuccess = true
            )
        } catch (e: Exception) {
            val took = 0L
            logger.error(e) { "Koog/OpenRouter analysis failed after all retries" }

            val snapshot = LastAiAnalysis(
                provider = getProviderName(),
                model = model.id,
                timestamp = Instant.now().toEpochMilli(),
                executionTimeMs = took,
                success = false,
                response = null,
                error = e.message
            )
            last.set(snapshot)

            AiAnalysisResponse(
                provider = snapshot.provider,
                model = snapshot.model,
                response = "",
                executionTimeMs = took,
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    fun getKeyMode(): RotatingOpenRouterClient.KeyMode = rotatingClient.getMode()

    fun getRotationStats() = rotatingClient.getRotationStats()
}
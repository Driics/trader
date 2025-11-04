// src/main/kotlin/ru/driics/aitrade/service/KoogAiService.kt
package ru.driics.aitrade.service

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
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
        val t0 = System.currentTimeMillis()

        return try {
            // Build Koog prompt using DSL
            val p = prompt(id = "signal-gen") {
                system(systemPrompt)
                user(prompt)
            }

            // Execute with rotating client
            val response = rotatingClient.execute(p, model)

            val took = System.currentTimeMillis() - t0

            // Extract response text from Message.Response
            val responseText = response.content

            val stats = rotatingClient.getRotationStats()
            logger.info { "API call successful (key ${stats.currentIndex + 1}/${stats.totalKeys}, ${took}ms)" }

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
            val took = System.currentTimeMillis() - t0
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

    /**
     * Returns current rotation statistics for monitoring.
     */
    fun getRotationStats() = rotatingClient.getRotationStats()
}
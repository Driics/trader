package ru.driics.aitrade.service

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import ru.driics.aitrade.model.AiAnalysisResponse
import ru.driics.aitrade.model.AiService
import ru.driics.aitrade.model.LastAiAnalysis
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Service
class KoogAiService(
    @param:Qualifier("openRouterExecutorMy")
    private val openRouterExecutorMy: SingleLLMPromptExecutor,
    @param:Value("\${ai.custom.system-prompt:You are an expert crypto trading analyst.}")
    private val systemPrompt: String,
    @param:Value("\${ai.koog.openrouter.api-key}")
    private val apiKey: String
): AiService {
    companion object {
        private val logger = KotlinLogging.logger {}
    }


    val deepSeekV31 = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "deepseek/deepseek-v3.2-exp",
        contextLength = 131_072,
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Completion
        )
    )


    private val last = AtomicReference<LastAiAnalysis?>(null)

    override fun getProviderName(): String = "koog-openrouter"

    override fun getModel(): String = deepSeekV31.id

    override fun getLastAnalysis(): LastAiAnalysis? = last.get()

    override suspend fun analyzePrompt(prompt: String): AiAnalysisResponse {
        val t0 = System.currentTimeMillis()

        return try {
            val p = prompt(id = "signal-gen") {
                system(systemPrompt)
                user(prompt)
            }

            val result = openRouterExecutorMy.execute(p, deepSeekV31)
            val took = System.currentTimeMillis() - t0

            val responseText = result[0].content
            val snapshot = LastAiAnalysis(
                provider = getProviderName(),
                model = deepSeekV31.id,
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
            logger.error(e) { "Koog/OpenRouter analysis failed" }
            val snapshot = LastAiAnalysis(
                provider = getProviderName(),
                model = deepSeekV31.id,
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
}

@Configuration
class KoogConfig {

    @Bean
    @Qualifier("openRouterExecutorMy")
    fun openRouterExecutorMy(
        @Value($$"${ai.koog.openrouter.api-key}") apiKey: String
    ): SingleLLMPromptExecutor {
        val client = OpenRouterLLMClient(
            apiKey = apiKey,
        )
        return SingleLLMPromptExecutor(client)
    }
}
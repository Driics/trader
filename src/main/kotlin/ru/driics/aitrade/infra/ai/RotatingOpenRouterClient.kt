// src/main/kotlin/ru/driics/aitrade/infra/ai/RotatingOpenRouterClient.kt
package ru.driics.aitrade.infra.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import ru.driics.aitrade.domain.services.ApiKeyRotationPolicy

/**
 * Rotating wrapper around OpenRouterLLMClient that handles 402 Payment Required errors
 * by automatically rotating through multiple API keys.
 *
 * Follows Clean Architecture: depends on domain service (ApiKeyRotationPolicy)
 * and wraps infrastructure client (OpenRouterLLMClient).
 */
class RotatingOpenRouterClient(
    private val rotationPolicy: ApiKeyRotationPolicy,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Executes a prompt with automatic key rotation on 402 errors.
     *
     * @param prompt The Koog prompt to execute
     * @param model The LLM model to use
     * @return Message.Response from OpenRouter
     */
    suspend fun execute(prompt: Prompt, model: LLModel): Message.Response {
        var lastException: Exception? = null
        val totalKeys = rotationPolicy.keyCount
        val maxAttempts = maxOf(maxRetries, totalKeys)

        for (attempt in 0 until maxAttempts) {
            val currentKey = if (attempt == 0) {
                rotationPolicy.currentKey
            } else {
                rotationPolicy.rotateToNextKey()
            }

            try {
                log.debug { "Attempting API call with key index ${rotationPolicy.currentIndex} (attempt ${attempt + 1}/$maxAttempts)" }

                val client = OpenRouterLLMClient(apiKey = currentKey)
                val response = client.execute(prompt, model)

                log.debug { "API call successful with key index ${rotationPolicy.currentIndex}" }
                return response[0]

            } catch (e: Exception) {
                lastException = e
                val errorMessage = e.message ?: ""

                if (is402PaymentRequired(errorMessage)) {
                    log.warn { "402 Payment Required for key index ${rotationPolicy.currentIndex}, rotating to next key" }

                    if (attempt < maxAttempts - 1) {
                        log.info { "Rotating to next API key (${attempt + 2}/$maxAttempts)" }
                        delay(retryDelayMs)
                        continue
                    } else {
                        log.error { "All $totalKeys API keys exhausted with 402 errors" }
                        throw Exception("All API keys returned 402 Payment Required", e)
                    }
                } else {
                    // Not a 402 error, don't retry
                    log.error(e) { "Non-402 error occurred: $errorMessage" }
                    throw e
                }
            }
        }

        throw lastException ?: Exception("Failed after $maxAttempts attempts")
    }

    /**
     * Detects if the error is a 402 Payment Required response.
     */
    private fun is402PaymentRequired(errorMessage: String): Boolean {
        return errorMessage.contains("402", ignoreCase = true) ||
                errorMessage.contains("Payment Required", ignoreCase = true) ||
                errorMessage.contains("payment required", ignoreCase = true)
    }

    /**
     * Returns current rotation statistics for monitoring.
     */
    fun getRotationStats(): RotationStats {
        return RotationStats(
            totalKeys = rotationPolicy.keyCount,
            currentIndex = rotationPolicy.currentIndex,
            currentKey = rotationPolicy.currentKey.take(10) + "..." // Masked for security
        )
    }
}

data class RotationStats(
    val totalKeys: Int,
    val currentIndex: Int,
    val currentKey: String
)
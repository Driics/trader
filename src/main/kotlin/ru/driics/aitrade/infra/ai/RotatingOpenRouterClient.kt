package ru.driics.aitrade.infra.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import ru.driics.aitrade.domain.services.ApiKeyRotationPolicy
import java.util.concurrent.atomic.AtomicIntegerArray

class RotatingOpenRouterClient(
    private val rotationPolicy: ApiKeyRotationPolicy,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000
) {
    companion object { private val log = KotlinLogging.logger {} }

    enum class KeyMode { SINGLE, MULTI }

    private val totalKeys = rotationPolicy.keyCount
    private val mode = if (rotationPolicy.isSingleKey) KeyMode.SINGLE else KeyMode.MULTI

    // Per-key usage counters (thread-safe)
    private val callsPerKey = AtomicIntegerArray(totalKeys)
    private val successesPerKey = AtomicIntegerArray(totalKeys)

    suspend fun execute(prompt: Prompt, model: LLModel): Message.Response {
        val maxAttempts = when (mode) {
            KeyMode.SINGLE -> 1 // 402 won't recover by retrying same key
            KeyMode.MULTI  -> maxOf(maxRetries, totalKeys)
        }

        var lastException: Exception? = null

        for (attempt in 0 until maxAttempts) {
            val keyUsed = if (attempt == 0) rotationPolicy.currentKey else rotationPolicy.rotateToNextKey()
            val idxUsed = rotationPolicy.currentIndex
            callsPerKey.incrementAndGet(idxUsed)

            try {
                log.debug { "OpenRouter call with key idx=$idxUsed mode=$mode attempt=${attempt + 1}/$maxAttempts" }
                val client = OpenRouterLLMClient(apiKey = keyUsed)
                val response = client.execute(prompt, model)
                successesPerKey.incrementAndGet(idxUsed)
                return response[0]
            } catch (e: Exception) {
                lastException = e
                val msg = e.message.orEmpty()
                if (is402PaymentRequired(msg) && mode == KeyMode.MULTI && attempt < maxAttempts - 1) {
                    log.warn { "402 on key idx=$idxUsed; rotating to next key..." }
                    delay(retryDelayMs)
                    continue
                }
                log.error(e) { "OpenRouter call failed (mode=$mode, idx=$idxUsed). ${if (!is402PaymentRequired(msg)) "Non-402 error, not retrying." else "No more retries."}" }
                throw e
            }
        }
        throw lastException ?: IllegalStateException("OpenRouter call failed after $maxAttempts attempt(s)")
    }

    private fun is402PaymentRequired(message: String): Boolean =
        message.contains("402", true) || message.contains("payment required", true)

    data class RotationStats(
        val totalKeys: Int,
        val currentIndex: Int,
        val currentKey: String, // masked preview
        val mode: KeyMode,
        val callsPerKey: List<Int>,
        val successesPerKey: List<Int>
    )

    fun getMode(): KeyMode = mode

    fun getRotationStats(): RotationStats {
        val calls = (0 until totalKeys).map { callsPerKey.get(it) }
        val succs = (0 until totalKeys).map { successesPerKey.get(it) }
        val maskedKey = rotationPolicy.currentKey.take(10) + "..."
        return RotationStats(
            totalKeys = totalKeys,
            currentIndex = rotationPolicy.currentIndex,
            currentKey = maskedKey,
            mode = mode,
            callsPerKey = calls,
            successesPerKey = succs
        )
    }
}

data class RotationStats(
    val totalKeys: Int,
    val currentIndex: Int,
    val currentKey: String
)
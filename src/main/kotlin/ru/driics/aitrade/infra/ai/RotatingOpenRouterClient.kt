package ru.driics.aitrade.infra.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.services.ApiKeyRotationPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray

class RotatingOpenRouterClient(
    private val rotationPolicy: ApiKeyRotationPolicy,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000
) {
    private companion object {
        val log = logger()
    }

    enum class KeyMode { SINGLE, MULTI }

    private val totalKeys = rotationPolicy.keyCount
    private val mode = if (rotationPolicy.isSingleKey) KeyMode.SINGLE else KeyMode.MULTI

    // Thread-safe metrics
    private val callsPerKey = AtomicIntegerArray(totalKeys)
    private val successesPerKey = AtomicIntegerArray(totalKeys)

    // Cache clients to avoid re-initializing HTTP pools/resources per request
    private val clientCache = ConcurrentHashMap<String, OpenRouterLLMClient>()

    suspend fun execute(prompt: Prompt, model: LLModel): Message.Response {
        // In Multi mode, we want to try at least 'maxRetries', but also cover all keys if possible
        val attempts = if (mode == KeyMode.SINGLE) 1 else maxOf(maxRetries, totalKeys)

        var lastException: Exception? = null

        repeat(attempts) { attempt ->
            // For the first attempt, use the current key. For subsequent, rotate first.
            if (attempt > 0) rotationPolicy.rotateToNextKey()

            val currentKey = rotationPolicy.currentKey
            val keyIndex = rotationPolicy.currentIndex

            // Track usage
            callsPerKey.incrementAndGet(keyIndex)

            try {
                log.debug { "OpenRouter execution: mode=$mode, keyIdx=$keyIndex, attempt=${attempt + 1}/$attempts" }

                val client = getOrCreateClient(currentKey)
                val responses = client.execute(prompt, model)

                val response = responses.firstOrNull()
                    ?: error("Received empty response list from OpenRouter")

                // Track success
                successesPerKey.incrementAndGet(keyIndex)
                return response

            } catch (e: Exception) {
                lastException = e
                val is402 = e.isPaymentRequired()

                if (is402 && mode == KeyMode.MULTI) {
                    log.warn { "402 Payment Required on key #$keyIndex. Rotating..." }
                    // Only delay if we are actually going to retry
                    if (attempt < attempts - 1) delay(retryDelayMs)
                } else {
                    // If it's not a 402 (e.g. 500 or 400), or we only have 1 key, we generally shouldn't rotate blindly.
                    // However, if you want to retry 500s, remove the check below.
                    // Current logic matches original: Fail fast on non-402.
                    log.error(e) { "OpenRouter failed (non-rotatable error or single key). Key #$keyIndex" }
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("OpenRouter failed after $attempts attempts")
    }

    private fun getOrCreateClient(apiKey: String): OpenRouterLLMClient {
        return clientCache.getOrPut(apiKey) {
            OpenRouterLLMClient(apiKey = apiKey)
        }
    }

    private fun Exception.isPaymentRequired(): Boolean {
        val msg = message.orEmpty()
        return msg.contains("402", ignoreCase = true) ||
                msg.contains("payment required", ignoreCase = true)
    }

    fun getMode(): KeyMode = mode

    fun getRotationStats(): RotationStats {
        // Snapshot atomic arrays to lists
        val calls = (0 until totalKeys).map { callsPerKey.get(it) }
        val successes = (0 until totalKeys).map { successesPerKey.get(it) }

        return RotationStats(
            totalKeys = totalKeys,
            currentIndex = rotationPolicy.currentIndex,
            currentKey = rotationPolicy.currentKey.mask(),
            mode = mode,
            callsPerKey = calls,
            successesPerKey = successes
        )
    }

    private fun String.mask(): String = if (length > 8) "${take(8)}..." else "***"

    data class RotationStats(
        val totalKeys: Int,
        val currentIndex: Int,
        val currentKey: String,
        val mode: KeyMode,
        val callsPerKey: List<Int>,
        val successesPerKey: List<Int>
    )
}
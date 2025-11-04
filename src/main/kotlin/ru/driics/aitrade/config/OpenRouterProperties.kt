package ru.driics.aitrade.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@ConfigurationProperties(prefix = "ai.koog.openrouter")
@Validated
data class OpenRouterProperties(
    var enabled: Boolean = true,
    var baseUrl: String = "https://openrouter.ai/api/v1",

    /**
     * Comma-separated list of API keys for rotation.
     * Example: "key1,key2,key3"
     */
    var apiKeys: String = "",

    /**
     * Maximum retry attempts when all keys fail.
     */
    var maxRetries: Int = 3,

    /**
     * Delay between retries in milliseconds.
     */
    var retryDelayMs: Long = 1000
) {
    /**
     * Parses the comma-separated API keys into a list.
     */
    fun getApiKeysList(): List<String> {
        return apiKeys.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
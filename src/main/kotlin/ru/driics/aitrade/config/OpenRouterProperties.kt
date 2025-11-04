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
    var apiKeys: String = "",
    var maxRetries: Int = 3,
    var retryDelayMs: Long = 1000
) {
    fun getApiKeysList(): List<String> {
        return apiKeys.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct() // ensure uniqueness for correct SINGLE/MULTI mode detection
    }
}
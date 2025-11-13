package ru.driics.aitrade.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "okx")
data class OkxProperties(
    var apiKey: String = "",
    var secretKey: String = "",
    var passphrase: String = "",
    var baseUrl: String = "https://www.okx.com",
    // WS settings
    var paper: Boolean = false,            // true for demo (wspap)
    var brokerId: String? = null           // required for wspap WS
) {
    fun wsBaseUrl(): String =
        if (paper) "wss://wspap.okx.com:8443" else "wss://ws.okx.com:8443"

    fun publicWsUrl(): String =
        if (paper) {
            val id = requireNotNull(brokerId) { "okx.broker-id must be set for paper WS" }
            "${wsBaseUrl()}/ws/v5/public?brokerId=$id"
        } else {
            "${wsBaseUrl()}/ws/v5/public"
        }

    fun privateWsUrl(): String =
        if (paper) {
            val id = requireNotNull(brokerId) { "okx.broker-id must be set for paper WS" }
            "${wsBaseUrl()}/ws/v5/private?brokerId=$id"
        } else {
            "${wsBaseUrl()}/ws/v5/private"
        }
}
@Component
@ConfigurationProperties(prefix = "prompt")
data class PromptProperties(
    var outputPath: String = "./prompt.txt"
)
package ru.driics.aitrade.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@ConfigurationProperties(prefix = "okx.api")
@Validated
data class OkxProperties(
    @field:NotBlank(message = "OKX API key is required")
    var key: String = "",
    @field:NotBlank(message = "OKX API secret is required")
    var secret: String = "",
    @field:NotBlank(message = "OKX API passphrase is required")
    var passphrase: String = "",
    var baseUrl: String = "https://www.okx.com"
)
@Component
@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    var currencies: String = "BTC,ETH,SOL,BNB,XRP,DOGE",
    var timeZone: String = "UTC",
    var autoExecute: Boolean = false
) {
    fun getCurrenciesList(): List<String> = currencies.split(",").map { it.trim() }
}

@Component
@ConfigurationProperties(prefix = "prompt")
data class PromptProperties(
    var outputPath: String = "./prompt.txt"
)
package ru.driics.aitrade.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "okx.api")
data class OkxProperties(
    var key: String = "",
    var secret: String = "",
    var passphrase: String = "",
    var baseUrl: String = "https://www.okx.com"
)

@Component
@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    var currencies: String = "BTC,ETH,SOL,BNB,XRP,DOGE",
    var timeZone: String = "UTC"
) {
    fun getCurrenciesList(): List<String> = currencies.split(",").map { it.trim() }
}

@Component
@ConfigurationProperties(prefix = "prompt")
data class PromptProperties(
    var outputPath: String = "./prompt.txt"
)
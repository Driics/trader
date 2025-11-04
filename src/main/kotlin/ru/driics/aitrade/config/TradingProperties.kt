package ru.driics.aitrade.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import ru.driics.aitrade.domain.model.MarginMode
import java.math.BigDecimal

@Component
@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    var currencies: String = "",
    var marginMode: String = "isolated",
    var autoExecute: Boolean = false,

    // Risk parameters
    var takerFeePct: BigDecimal = BigDecimal("0.0005"),
    var marginBufferPct: BigDecimal = BigDecimal("0.02"),
    var minConfidence: BigDecimal = BigDecimal("0.60"),
    var maxLeverage: Int = 40,
    var minLeverage: Int = 5,

    // API limits
    var maxConcurrentSymbols: Int = 4,
    var instrumentCacheTtlMinutes: Long = 10
) {
    fun getCurrenciesList(): List<String> =
        currencies.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }

    fun getMarginMode(): MarginMode = MarginMode.fromString(marginMode)
}

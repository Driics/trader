package ru.driics.aitrade.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import ru.driics.aitrade.domain.model.MarginMode
import java.math.BigDecimal
import java.time.Duration
import java.util.*

@Component
@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    var currencies: List<String> = emptyList(),
    var marginMode: String = "isolated",
    var autoExecute: Boolean = false,

    @field:DecimalMin(value = "0.0", message = "Taker fee must be non-negative")
    @field:DecimalMax(value = "1.0", message = "Taker fee cannot exceed 100%")
    var takerFeePct: BigDecimal = BigDecimal("0.0005"),

    @field:DecimalMin(value = "0.0", message = "Margin buffer must be non-negative")
    @field:DecimalMax(value = "1.0", message = "Margin buffer cannot exceed 100%")
    var marginBufferPct: BigDecimal = BigDecimal("0.02"),

    @field:DecimalMin(value = "0.0", message = "Min confidence must be non-negative")
    @field:DecimalMax(value = "1.0", message = "Min confidence cannot exceed 1.0")
    var minConfidence: BigDecimal = BigDecimal("0.60"),

    @field:Min(value = 1, message = "Max leverage must be at least 1")
    @field:Max(value = 125, message = "Max leverage cannot exceed 125")
    var maxLeverage: Int = 40,

    @field:Min(value = 1, message = "Min leverage must be at least 1")
    var minLeverage: Int = 5,

    @field:Min(value = 1, message = "Max concurrent symbols must be at least 1")
    var maxConcurrentSymbols: Int = 4,

    var instrumentCacheTtl: Duration = Duration.ofHours(1), // Changed to 1 hour for aggressive caching

    var okxTimeouts: OkxTimeouts = OkxTimeouts(),

    // AI call configuration
    var aiModel: String = "qwen/qwen3-max", // Default AI model
    var aiTimeoutMs: Long = 60_000, // 60 seconds default timeout
    var aiMaxRetries: Int = 2, // Retries for transient errors
    var aiBudgetPerMinute: Long = 10, // Max AI requests per minute
    var aiCooldownMs: Duration? = Duration.ofMinutes(5), // Cooldown between trades per symbol

    // Execution Mode
    var demoMode: Boolean = true // If true, orders are simulated (dry-run)
) {
    fun getCurrenciesList(): List<String> =
        currencies.map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }

    fun getMarginMode(): MarginMode = MarginMode.fromString(marginMode)
}

data class OkxTimeouts(
    var ticker: Duration = Duration.ofSeconds(5),
    var candles: Duration = Duration.ofSeconds(10),
    var funding: Duration = Duration.ofSeconds(5),
    var openInterest: Duration = Duration.ofSeconds(5),
    var account: Duration = Duration.ofSeconds(10),
    var positions: Duration = Duration.ofSeconds(10),
    var bills: Duration = Duration.ofSeconds(10),
    var instruments: Duration = Duration.ofSeconds(10),
    var setLeverage: Duration = Duration.ofSeconds(10),
    var placeOrder: Duration = Duration.ofSeconds(15)
)
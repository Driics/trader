package ru.driics.aitrade.application.ai

import ru.driics.aitrade.application.ai.common.TimeBasedCache
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration

/**
 * Calibrates confidence scores and enforces cooldown periods per symbol.
 * Filters weak signals and prevents "chatter" during flat markets.
 * 
 * Thread-safe implementation using TimeBasedCache.
 */
class ConfidenceCalibrator(
    tradingProperties: TradingProperties,
    clock: Clock
) {
    companion object {
        private val log = logger<ConfidenceCalibrator>()
        private val DEFAULT_COOLDOWN = Duration.ofMinutes(5)
        private const val MILLIS_PER_SECOND = 1_000L
    }

    private val cooldown = tradingProperties.aiCooldownMs ?: DEFAULT_COOLDOWN
    private val minConfidence = tradingProperties.minConfidence
    private val lastTradeTime = TimeBasedCache<String>(
        clock = clock,
        ttl = cooldown,
        cleanupThreshold = 100
    )

    /**
     * Checks if signal should be accepted based on confidence and cooldown.
     * Returns Accepted if signal should be processed, Rejected with reason otherwise.
     */
    fun shouldAccept(signal: AiTradeSignalArgs): CalibrationResult {
        val symbol = signal.coin.uppercase()

        // 1. Check minimum confidence threshold
        val confidence = signal.confidence ?: BigDecimal.ZERO
        if (confidence < minConfidence) {
            val reason = "Confidence $confidence below minimum $minConfidence"
            log.debug { "Signal rejected for $symbol: $reason" }
            return CalibrationResult.Rejected(reason)
        }

        // 2. Check cooldown period (only for BUY/SELL, not HOLD)
        if (signal.signal != AiSignal.HOLD && lastTradeTime.contains(symbol)) {
            val remainingSeconds = lastTradeTime.getRemainingTtlMs(symbol) / MILLIS_PER_SECOND
            val reason = "Cooldown active: ${remainingSeconds}s remaining (${cooldown.toSeconds()}s total)"
            log.debug { "Signal rejected for $symbol: $reason" }
            return CalibrationResult.Rejected(reason)
        }

        // 3. Signal accepted
        return CalibrationResult.Accepted
    }

    /**
     * Records that a trade was executed for a symbol (updates cooldown timer).
     */
    fun recordTrade(symbol: String) {
        lastTradeTime.put(symbol.uppercase())
    }

    /**
     * Gets remaining cooldown time for a symbol in seconds.
     */
    fun getRemainingCooldownSeconds(symbol: String): Long =
        lastTradeTime.getRemainingTtlMs(symbol.uppercase()) / MILLIS_PER_SECOND

    sealed class CalibrationResult {
        object Accepted : CalibrationResult()
        data class Rejected(val reason: String) : CalibrationResult()
    }
}

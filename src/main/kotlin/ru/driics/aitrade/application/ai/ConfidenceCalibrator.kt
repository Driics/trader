package ru.driics.aitrade.application.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Calibrates confidence scores and enforces cooldown periods per symbol.
 * Filters weak signals and prevents "chatter" during flat markets.
 */
class ConfidenceCalibrator(
    private val tradingProperties: TradingProperties,
    private val clock: Clock
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val lastTradeTime = ConcurrentHashMap<String, Long>()
    private val cooldownMs = tradingProperties.aiCooldownMs?.toMillis() ?: Duration.ofMinutes(5).toMillis()

    /**
     * Checks if signal should be accepted based on confidence and cooldown.
     * Returns true if signal should be processed, false if it should be skipped.
     */
    fun shouldAccept(signal: AiTradeSignalArgs): CalibrationResult {
        val symbol = signal.coin.uppercase()

        // 1. Check minimum confidence threshold
        val confidence = signal.confidence ?: BigDecimal.ZERO
        val minConfidence = tradingProperties.minConfidence

        if (confidence < minConfidence) {
            val reason = "Confidence $confidence below minimum $minConfidence"
            log.debug { "Signal rejected for $symbol: $reason" }
            return CalibrationResult.Rejected(reason)
        }

        // 2. Check cooldown period
        if (signal.signal != ru.driics.aitrade.domain.model.AiSignal.HOLD) {
            val lastTrade = lastTradeTime[symbol]
            val now = clock.instant().toEpochMilli()

            if (lastTrade != null) {
                val elapsed = now - lastTrade
                if (elapsed < cooldownMs) {
                    val remainingSeconds = (cooldownMs - elapsed) / 1000
                    val reason = "Cooldown active: ${remainingSeconds}s remaining (${cooldownMs / 1000}s total)"
                    log.debug { "Signal rejected for $symbol: $reason" }
                    return CalibrationResult.Rejected(reason)
                }
            }
        }

        // 3. Signal accepted
        return CalibrationResult.Accepted
    }

    /**
     * Records that a trade was executed for a symbol (updates cooldown timer).
     */
    fun recordTrade(symbol: String) {
        val now = clock.instant().toEpochMilli()
        lastTradeTime[symbol] = now

        // Cleanup old entries periodically
        if (lastTradeTime.size > 100) {
            cleanupExpired()
        }
    }

    /**
     * Gets remaining cooldown time for a symbol in seconds.
     */
    fun getRemainingCooldownSeconds(symbol: String): Long {
        val lastTrade = lastTradeTime[symbol] ?: return 0
        val now = clock.instant().toEpochMilli()
        val elapsed = now - lastTrade
        return if (elapsed < cooldownMs) {
            (cooldownMs - elapsed) / 1000
        } else {
            0
        }
    }

    private fun cleanupExpired() {
        val now = clock.instant().toEpochMilli()
        val expired = lastTradeTime.entries.filter { (now - it.value) >= cooldownMs }
        expired.forEach { lastTradeTime.remove(it.key) }
        if (expired.isNotEmpty()) {
            log.debug { "Cleaned up ${expired.size} expired cooldown entries" }
        }
    }

    sealed class CalibrationResult {
        object Accepted : CalibrationResult()
        data class Rejected(val reason: String) : CalibrationResult()
    }
}


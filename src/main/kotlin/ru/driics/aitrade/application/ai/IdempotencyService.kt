package ru.driics.aitrade.application.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for ensuring idempotency of AI trading decisions.
 * Prevents duplicate orders from the same signal within a time window.
 */
class IdempotencyService(
    private val clock: Clock,
    private val ttlMinutes: Long = 2
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val seenSignals = ConcurrentHashMap<String, Long>()

    /**
     * Generates a deterministic clOrdId from signal parameters.
     * Same signal parameters will produce the same clOrdId.
     */
    fun generateClOrdId(symbol: String, signal: AiTradeSignalArgs, entryPrice: java.math.BigDecimal, timestamp: Long): String {
        // Create a deterministic hash from signal parameters
        val hashInput = buildString {
            append(symbol.uppercase())
            append("|")
            append(signal.signal.name)
            append("|")
            append(entryPrice.toPlainString())
            append("|")
            append(signal.profitTarget?.toPlainString() ?: "")
            append("|")
            append(signal.stopLoss?.toPlainString() ?: "")
            append("|")
            append(signal.leverage ?: "")
            append("|")
            // Round timestamp to nearest minute for idempotency window
            append(timestamp / 60000)
        }

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(hashInput.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

        val head = ("AI" + symbol.filter { it.isLetterOrDigit() }.uppercase()).take(8)
        val ts36 = (timestamp / 1000).toString(36).uppercase()
        val hashShort = hash.take(6).uppercase()

        return "$head$ts36$hashShort".take(32)
    }

    /**
     * Checks if this signal was already processed recently.
     * Returns true if it's a duplicate (should skip), false if it's new.
     */
    fun isDuplicate(signalKey: String): Boolean {
        val now = clock.instant().toEpochMilli()
        val seen = seenSignals[signalKey]

        return if (seen != null && (now - seen) < Duration.ofMinutes(ttlMinutes).toMillis()) {
            log.debug { "Duplicate signal detected: $signalKey (seen ${(now - seen) / 1000}s ago)" }
            true
        } else {
            false
        }
    }

    /**
     * Records that a signal was processed.
     */
    fun recordSignal(signalKey: String) {
        val now = clock.instant().toEpochMilli()
        seenSignals[signalKey] = now

        // Cleanup old entries periodically (simple approach: on every 100th call)
        if (seenSignals.size > 1000) {
            cleanupExpired()
        }
    }

    /**
     * Generates a unique key for a signal for deduplication.
     */
    fun signalKey(symbol: String, signal: AiTradeSignalArgs, entryPrice: java.math.BigDecimal, timestampWindowMinutes: Long = 2): String {
        val window = (clock.instant().toEpochMilli() / (timestampWindowMinutes * 60 * 1000))
        return buildString {
            append(symbol.uppercase())
            append("|")
            append(signal.signal.name)
            append("|")
            append(entryPrice.toPlainString())
            append("|")
            append(signal.profitTarget?.toPlainString() ?: "")
            append("|")
            append(signal.stopLoss?.toPlainString() ?: "")
            append("|")
            append(signal.leverage ?: "")
            append("|")
            append(window)
        }
    }

    private fun cleanupExpired() {
        val now = clock.instant().toEpochMilli()
        val ttlMs = Duration.ofMinutes(ttlMinutes).toMillis()
        val expired = seenSignals.entries.filter { (now - it.value) >= ttlMs }
        expired.forEach { seenSignals.remove(it.key) }
        if (expired.isNotEmpty()) {
            log.debug { "Cleaned up ${expired.size} expired idempotency entries" }
        }
    }
}


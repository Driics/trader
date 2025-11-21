package ru.driics.aitrade.application.ai

import ru.driics.aitrade.application.ai.common.TimeBasedCache
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/**
 * Service for ensuring idempotency of AI trading decisions.
 * Prevents duplicate orders from the same signal within a time window.
 * 
 * Uses deterministic hashing to generate clOrdId from signal parameters.
 */
class IdempotencyService(
    private val clock: Clock,
    ttlMinutes: Long = 2
) {
    companion object {
        private val log = logger<IdempotencyService>()
        private const val CL_ORD_ID_MAX_LENGTH = 32
        private const val HASH_BYTES = 8
        private const val HASH_DISPLAY_LENGTH = 6
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }

    private val seenSignals = TimeBasedCache<String>(
        clock = clock,
        ttl = Duration.ofMinutes(ttlMinutes),
        cleanupThreshold = 1000
    )

    /**
     * Generates a deterministic clOrdId from signal parameters.
     * Same signal parameters will produce the same clOrdId.
     */
    fun generateClOrdId(
        symbol: String,
        signal: AiTradeSignalArgs,
        entryPrice: BigDecimal,
        timestamp: Long
    ): String {
        val hashInput = buildSignalHashInput(symbol, signal, entryPrice, timestamp)
        val hash = computeHash(hashInput)
        
        val prefix = buildClOrdIdPrefix(symbol)
        val timestampBase36 = (timestamp / MILLIS_PER_SECOND).toString(36).uppercase()
        val hashSuffix = hash.take(HASH_DISPLAY_LENGTH).uppercase()

        return "$prefix$timestampBase36$hashSuffix".take(CL_ORD_ID_MAX_LENGTH)
    }

    private fun buildSignalHashInput(
        symbol: String,
        signal: AiTradeSignalArgs,
        entryPrice: BigDecimal,
        timestamp: Long
    ): String = buildString {
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
        append(timestamp / MILLIS_PER_MINUTE) // Round to nearest minute
    }

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }
    }

    private fun buildClOrdIdPrefix(symbol: String): String =
        ("AI" + symbol.filter { it.isLetterOrDigit() }.uppercase()).take(8)

    /**
     * Checks if this signal was already processed recently.
     * Returns true if it's a duplicate (should skip), false if it's new.
     */
    fun isDuplicate(signalKey: String): Boolean {
        if (!seenSignals.contains(signalKey)) return false
        
        val remainingSeconds = seenSignals.getRemainingTtlMs(signalKey) / MILLIS_PER_SECOND
        log.debug { "Duplicate signal detected: $signalKey (${remainingSeconds}s remaining)" }
        return true
    }

    /**
     * Records that a signal was processed.
     */
    fun recordSignal(signalKey: String) {
        seenSignals.put(signalKey)
    }

    /**
     * Generates a unique key for a signal for deduplication.
     */
    fun signalKey(
        symbol: String,
        signal: AiTradeSignalArgs,
        entryPrice: BigDecimal,
        timestampWindowMinutes: Long = 2
    ): String {
        val windowMs = timestampWindowMinutes * MILLIS_PER_MINUTE
        val window = clock.instant().toEpochMilli() / windowMs
        
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
}


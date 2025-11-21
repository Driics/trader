package ru.driics.aitrade.application.ai.common

import ru.driics.aitrade.common.logging.logger
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic time-based cache with automatic cleanup.
 * Used by IdempotencyService and ConfidenceCalibrator to avoid code duplication.
 */
class TimeBasedCache<K>(
    val clock: Clock,
    private val ttl: Duration,
    private val cleanupThreshold: Int = 100
) {
    companion object {
        private val log = logger<TimeBasedCache<*>>()
    }

    private val cache = ConcurrentHashMap<K, Long>()

    /**
     * Checks if key exists and is still valid (not expired).
     */
    fun contains(key: K): Boolean {
        val timestamp = cache[key] ?: return false
        return !isExpired(timestamp)
    }

    private fun isExpired(timestamp: Long): Boolean {
        val now = clock.instant().toEpochMilli()
        val elapsed = now - timestamp
        return elapsed >= ttl.toMillis()
    }

    /**
     * Stores a key with current timestamp.
     */
    fun put(key: K) {
        val now = clock.instant().toEpochMilli()
        cache[key] = now

        // Cleanup if needed
        if (cache.size > cleanupThreshold) {
            cleanupExpired()
        }
    }

    /**
     * Gets remaining TTL for a key in milliseconds.
     */
    fun getRemainingTtlMs(key: K): Long {
        val timestamp = cache[key] ?: return 0
        if (isExpired(timestamp)) return 0
        
        val now = clock.instant().toEpochMilli()
        val elapsed = now - timestamp
        return ttl.toMillis() - elapsed
    }

    /**
     * Removes expired entries from cache.
     */
    private fun cleanupExpired() {
        val expired = cache.entries.filter { isExpired(it.value) }
        expired.forEach { cache.remove(it.key) }
        if (expired.isNotEmpty()) {
            log.debug { "Cleaned up ${expired.size} expired cache entries" }
        }
    }
}


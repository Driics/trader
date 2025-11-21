package ru.driics.aitrade.infra.cache

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Type-safe cache get operation with proper Redis deserialization support.
 * Uses reified type parameter to enable proper type inference for Redis deserialization.
 */
suspend inline fun <reified T> SmartCacheStrategy.getTyped(
    key: String,
    level: SmartCacheStrategy.CacheLevel,
    noinline fetcher: suspend () -> T,
    ttl: Duration? = null
): T {
    // Try L1 first
    if (level == SmartCacheStrategy.CacheLevel.L1) {
        @Suppress("UNCHECKED_CAST")
        val cached = getL1Cache().getIfPresent(key) as T?
        if (cached != null) return cached
    }

    // Try L3
    if (level == SmartCacheStrategy.CacheLevel.L3) {
        @Suppress("UNCHECKED_CAST")
        val cached = getL3Cache().getIfPresent(key) as T?
        if (cached != null) {
            // Promote to L1 for faster access
            getL1Cache().put(key, cached as Any)
            return cached
        }
    }

    // Try Redis with type information (using reified T)
    if (level == SmartCacheStrategy.CacheLevel.L2 || level == SmartCacheStrategy.CacheLevel.L3) {
        val redisValue = withContext(Dispatchers.IO) {
            getRedisAdapter().get(key)
        }
        
        if (redisValue != null) {
            try {
                val deserialized: T = getObjectMapper().readValue(redisValue)
                // Promote to L1 and L3
                getL1Cache().put(key, deserialized as Any)
                if (level == SmartCacheStrategy.CacheLevel.L3) {
                    getL3Cache().put(key, deserialized as Any)
                }
                return deserialized
            } catch (e: Exception) {
                // Fall through to fetch if deserialization fails
            }
        }
    }

    // Fall back to base implementation (which handles locking and write-through)
    return get(key, level, fetcher, ttl)
}


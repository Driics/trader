package ru.driics.aitrade.infra.cache

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-level caching strategy with read-through and write-through patterns.
 * 
 * L1: Ultra-fast in-memory cache (Caffeine) - 1s TTL
 * L2: Distributed cache (Redis, optional) - 1 min TTL
 * L3: Long-term in-memory cache (Caffeine) - 1 hour TTL
 */
@Component
class SmartCacheStrategy(
    private val redisAdapter: RedisCacheAdapter,
    private val objectMapper: ObjectMapper
) {
    private val log = KotlinLogging.logger {}

    // Level 1: Ultra-fast in-memory cache (1s TTL)
    private val l1Cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(1))
        .maximumSize(1000)
        .recordStats()
        .build<String, Any>()

    // Level 3: Long-term in-memory cache (1 hour TTL)
    private val l3Cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .maximumSize(10000)
        .recordStats()
        .build<String, Any>()

    // Prevent thundering herd (one load per key)
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Read-through pattern: Check L1 -> L2 (Redis) -> L3 -> Fetch -> Write-through
     * 
     * @param key Cache key
     * @param level Cache level to use
     * @param fetcher Function to fetch data if not in cache
     * @param ttl Optional TTL override for L2 (Redis)
     */
    suspend fun <T> get(
        key: String,
        level: CacheLevel,
        fetcher: suspend () -> T,
        ttl: Duration? = null
    ): T {
        // Try L1 first (fastest)
        if (level == CacheLevel.L1) {
            @Suppress("UNCHECKED_CAST")
            val cached = l1Cache.getIfPresent(key) as T?
            if (cached != null) {
                log.trace { "Cache HIT L1: $key" }
                return cached
            }
        }

        // Try L3 for long-term cache
        if (level == CacheLevel.L3) {
            @Suppress("UNCHECKED_CAST")
            val cached = l3Cache.getIfPresent(key) as T?
            if (cached != null) {
                log.trace { "Cache HIT L3: $key" }
                // Promote to L1 for faster access
                if (cached != null) l1Cache.put(key, cached as Any)
                return cached
            }
        }

        // Try L2 (Redis) if enabled
        // Note: Redis deserialization requires type information which is lost at runtime.
        // For now, Redis read is skipped for generic types. To enable, add overloaded methods
        // with explicit type parameters or use TypeReference.
        // This is a limitation of Java/Kotlin generics - the type T is erased at runtime.

        // Cache miss - fetch with lock to prevent thundering herd
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return try {
            mutex.withLock {
                // Double-check after acquiring lock
                @Suppress("UNCHECKED_CAST")
                val secondCheck = when (level) {
                    CacheLevel.L1 -> l1Cache.getIfPresent(key) as T?
                    CacheLevel.L2 -> null // Already checked Redis
                    CacheLevel.L3 -> l3Cache.getIfPresent(key) as T?
                }
                if (secondCheck != null) {
                    log.trace { "Cache HIT (double-check): $key" }
                    return@withLock secondCheck
                }

                // Fetch from source
                log.debug { "Cache MISS: $key, fetching from source" }
                val loaded = fetcher()

                // Write-through: Write to all cache levels
                if (loaded != null) {
                    writeThrough(key, loaded, level, ttl)
                }

                loaded
            }
        } finally {
            locks.remove(key, mutex)
        }
    }

    /**
     * Write-through pattern: Write to all cache levels.
     */
    private suspend fun <T> writeThrough(
        key: String,
        value: T,
        level: CacheLevel,
        ttl: Duration?
    ) {
        try {
            // Always write to L1 for fast access
            l1Cache.put(key, value as Any)

            // Write to L2 (Redis) if L2 or L3
            // Note: Redis serialization works for simple types. For complex types, 
            // consider using TypeReference or passing Class<T> parameter
            if (level == CacheLevel.L2 || level == CacheLevel.L3) {
                try {
                    val serialized = objectMapper.writeValueAsString(value)
                    val redisTtl = ttl ?: Duration.ofMinutes(1)
                    withContext(Dispatchers.IO) {
                        redisAdapter.set(key, serialized, redisTtl)
                    }
                } catch (e: Exception) {
                    log.warn(e) { "Failed to serialize value for Redis cache: $key" }
                }
            }

            // Write to L3 for long-term storage
            if (level == CacheLevel.L3) {
                l3Cache.put(key, value as Any)
            }

            log.trace { "Write-through completed for key: $key, level: $level" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to write-through cache for key: $key" }
        }
    }

    /**
     * Explicit write operation (write-through pattern).
     */
    suspend fun <T> put(
        key: String,
        value: T,
        level: CacheLevel,
        ttl: Duration? = null
    ) {
        if (value == null) return
        writeThrough(key, value, level, ttl)
    }

    /**
     * Invalidate cache entry from all levels.
     */
    suspend fun invalidate(key: String, level: CacheLevel? = null) {
        if (level == null || level == CacheLevel.L1) {
            l1Cache.invalidate(key)
        }
        if (level == null || level == CacheLevel.L2) {
            withContext(Dispatchers.IO) {
                redisAdapter.delete(key)
            }
        }
        if (level == null || level == CacheLevel.L3) {
            l3Cache.invalidate(key)
        }
        log.debug { "Cache invalidated: $key, level: ${level ?: "all"}" }
    }

    fun invalidateAll(level: CacheLevel) {
        when (level) {
            CacheLevel.L1 -> l1Cache.invalidateAll()
            CacheLevel.L2 -> {
                // Redis invalidation would need to be implemented
                log.warn { "Redis invalidateAll not implemented" }
            }
            CacheLevel.L3 -> l3Cache.invalidateAll()
        }
    }

    private fun cacheFor(level: CacheLevel): Cache<String, Any> =
        when (level) {
            CacheLevel.L1 -> l1Cache
            CacheLevel.L2 -> throw UnsupportedOperationException("L2 uses Redis, not Caffeine")
            CacheLevel.L3 -> l3Cache
        }

    /**
     * Get cache statistics for monitoring.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "l1" to l1Cache.stats(),
            "l3" to l3Cache.stats(),
            "redisEnabled" to (redisAdapter !is NoOpRedisCacheAdapter)
        )
    }

    // Internal accessors for extension functions
    fun getL1Cache() = l1Cache
    fun getL3Cache() = l3Cache
    fun getRedisAdapter() = redisAdapter
    fun getObjectMapper() = objectMapper

    enum class CacheLevel { L1, L2, L3 }
}
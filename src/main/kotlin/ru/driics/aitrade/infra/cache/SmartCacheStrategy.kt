package ru.driics.aitrade.infra.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class SmartCacheStrategy {

    // Level 1: Ultra-fast in-memory cache (1s TTL)
    private val l1Cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(1))
        .maximumSize(1000)
        .recordStats()
        .build<String, Any>()

    // Level 2: Short-term cache (1 min TTL)
    private val l2Cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .maximumSize(5000)
        .recordStats()
        .build<String, Any>()

    // Level 3: Long-term cache (1 hour TTL)
    private val l3Cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .maximumSize(10000)
        .recordStats()
        .build<String, Any>()

    // Prevent thundering herd (one load per key)
    private val locks = ConcurrentHashMap<String, Mutex>()

    // Note: by design we do NOT cache nulls. If fetcher returns null, we skip put().
    suspend fun <T> get(
        key: String,
        level: CacheLevel,
        fetcher: suspend () -> T
    ): T {
        val cache = cacheFor(level)

        @Suppress("UNCHECKED_CAST")
        val cached = cache.getIfPresent(key) as T?
        if (cached != null) return cached

        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return try {
            mutex.withLock {
                // Double-check after acquiring the lock
                @Suppress("UNCHECKED_CAST")
                val secondCheck = cache.getIfPresent(key) as T?
                if (secondCheck != null) return@withLock secondCheck

                val loaded = fetcher()
                // only cache non-null values
                if (loaded != null) cache.put(key, loaded as Any)
                loaded
            }
        } finally {
            locks.remove(key, mutex)
        }
    }

    fun invalidate(key: String, level: CacheLevel) {
        cacheFor(level).invalidate(key)
        locks.remove(key)
    }

    fun invalidateAll(level: CacheLevel) {
        cacheFor(level).invalidateAll()
        locks.clear()
    }

    private fun cacheFor(level: CacheLevel): Cache<String, Any> =
        when (level) {
            CacheLevel.L1 -> l1Cache
            CacheLevel.L2 -> l2Cache
            CacheLevel.L3 -> l3Cache
        }

    enum class CacheLevel { L1, L2, L3 }
}
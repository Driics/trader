package ru.driics.aitrade.infra.exchange

import com.github.benmanes.caffeine.cache.Caffeine
import ru.driics.aitrade.model.OkxCandleResponse
import java.time.Duration

/**
 * Cache for 4H candle data to avoid recalculations when data hasn't changed.
 * Key: symbol, Value: Cached4HData
 */
data class Cached4HData(
    val candles: List<OkxCandleResponse>,
    val lastCandleTimestamp: Long,
    val dataHash: String
)

class Candles4HCache {
    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(5))
        .maximumSize(50)
        .build<String, Cached4HData>()

    fun get(symbol: String): Cached4HData? = cache.getIfPresent(symbol)

    fun put(symbol: String, data: Cached4HData) {
        cache.put(symbol, data)
    }

    fun invalidate(symbol: String) {
        cache.invalidate(symbol)
    }

    fun clear() {
        cache.invalidateAll()
    }

    companion object {
        /**
         * Generate hash from candle data to detect changes.
         * Uses last candle timestamp + close price as simple hash.
         */
        fun generateHash(candles: List<OkxCandleResponse>): String {
            if (candles.isEmpty()) return "empty"
            val last = candles.last()
            return "${last.timestamp}_${last.close}"
        }
    }
}
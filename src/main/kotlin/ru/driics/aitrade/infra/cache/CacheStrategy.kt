package ru.driics.aitrade.infra.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import ru.driics.aitrade.model.OkxCandleResponse
import ru.driics.aitrade.model.OkxInstrumentInfo
import ru.driics.aitrade.model.OkxTickerResponse
import java.time.Duration

@Component
class CacheStrategy {
    // Ultra-short TTL for frequently changing data
    private val tickerCache: Cache<String, CachedTicker> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(1))
        .maximumSize(100)
        .recordStats()
        .build()

    // Short TTL for moderately changing data
    private val candleCache: Cache<CandleKey, CachedCandles> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .maximumSize(500)
        .recordStats()
        .build()

    // Long TTL for rarely changing data
    private val instrumentCache: Cache<String, OkxInstrumentInfo> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .maximumSize(200)
        .recordStats()
        .build()

    // In-memory cache for computed indicators
    private val indicatorCache: Cache<IndicatorKey, CachedIndicator> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(1000)
        .recordStats()
        .build()

    fun getTicker(instId: String): OkxTickerResponse? =
        tickerCache.getIfPresent(instId)?.ticker

    fun putTicker(instId: String, ticker: OkxTickerResponse) =
        tickerCache.put(instId, CachedTicker(ticker, System.currentTimeMillis()))

    fun getCandles(instId: String, period: String): List<OkxCandleResponse>? {
        val key = CandleKey(instId, period)
        return candleCache.getIfPresent(key)?.candles
    }

    fun putCandles(instId: String, period: String, candles: List<OkxCandleResponse>) {
        val key = CandleKey(instId, period)
        candleCache.put(key, CachedCandles(candles, System.currentTimeMillis()))
    }

    fun getInstrument(instId: String) =
        instrumentCache.getIfPresent(instId)

    fun putInstrument(instId: String, instrument: OkxInstrumentInfo) =
        instrumentCache.put(instId, instrument)

    fun getIndicator(instId: String, period: String, indicatorName: String): Any? {
        val key = IndicatorKey(instId, period, indicatorName)
        return indicatorCache.getIfPresent(key)?.value
    }

    fun putIndicator(instId: String, period: String, indicatorName: String, value: Any) {
        val key = IndicatorKey(instId, period, indicatorName)
        indicatorCache.put(key, CachedIndicator(value, System.currentTimeMillis()))
    }

    data class CachedTicker(val ticker: OkxTickerResponse, val timestamp: Long)
    data class CachedCandles(val candles: List<OkxCandleResponse>, val timestamp: Long)
    data class CachedIndicator(val value: Any, val timestamp: Long)

    data class CandleKey(val instId: String, val period: String)
    data class IndicatorKey(val instId: String, val period: String, val name: String)

    data class CacheStats(
        val hitCount: Long,
        val missCount: Long,
        val hitRate: Double,
        val evictionCount: Long
    )

    fun getCacheStats(): Map<String, CacheStats> {
        return mapOf(
            "ticker" to tickerCache.stats().toCacheStats(),
            "candle" to candleCache.stats().toCacheStats(),
            "instrument" to instrumentCache.stats().toCacheStats(),
            "indicator" to indicatorCache.stats().toCacheStats()
        )
    }

    private fun com.github.benmanes.caffeine.cache.stats.CacheStats.toCacheStats(): CacheStats {
        return CacheStats(
            hitCount = hitCount(),
            missCount = missCount(),
            hitRate = hitRate(),
            evictionCount = evictionCount()
        )
    }
}
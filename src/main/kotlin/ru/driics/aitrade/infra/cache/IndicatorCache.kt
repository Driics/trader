package ru.driics.aitrade.infra.cache

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.driics.aitrade.domain.services.IndicatorCalculator
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration

/**
 * Cache for indicator calculations to avoid recomputing the same indicators.
 * Uses hash of input prices as cache key.
 */
@Component
class IndicatorCache {
    private val log = KotlinLogging.logger {}

    private val emaCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(1000)
        .recordStats()
        .build<String, BigDecimal>()

    private val macdCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(1000)
        .recordStats()
        .build<String, BigDecimal>()

    private val rsiCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(1000)
        .recordStats()
        .build<String, BigDecimal>()

    private val progressiveEmaCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(500)
        .recordStats()
        .build<String, List<BigDecimal>>()

    private val progressiveMacdCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(500)
        .recordStats()
        .build<String, List<BigDecimal>>()

    private val progressiveRsiCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(500)
        .recordStats()
        .build<String, List<BigDecimal>>()

    private val atrCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(500)
        .recordStats()
        .build<String, BigDecimal>()

    /**
     * Calculate EMA with caching.
     */
    fun calculateEMA(prices: List<BigDecimal>, period: Int): BigDecimal {
        val key = generateKey("ema", prices, period)
        return emaCache.get(key) { IndicatorCalculator.calculateEMA(prices, period) }
    }

    /**
     * Calculate MACD with caching.
     */
    fun calculateMACD(prices: List<BigDecimal>): BigDecimal {
        val key = generateKey("macd", prices, null)
        return macdCache.get(key) { IndicatorCalculator.calculateMACD(prices) }
    }

    /**
     * Calculate RSI with caching.
     */
    fun calculateRSI(prices: List<BigDecimal>, period: Int): BigDecimal {
        val key = generateKey("rsi", prices, period)
        return rsiCache.get(key) { IndicatorCalculator.calculateRSI(prices, period) }
    }

    /**
     * Calculate progressive EMA with caching.
     */
    fun calculateProgressiveEMA(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        val key = generateKey("prog_ema", prices, period)
        return progressiveEmaCache.get(key) { IndicatorCalculator.calculateProgressiveEMA(prices, period) }
    }

    /**
     * Calculate progressive MACD with caching.
     */
    fun calculateProgressiveMACD(prices: List<BigDecimal>): List<BigDecimal> {
        val key = generateKey("prog_macd", prices, null)
        return progressiveMacdCache.get(key) { IndicatorCalculator.calculateProgressiveMACD(prices) }
    }

    /**
     * Calculate progressive RSI with caching.
     */
    fun calculateProgressiveRSI(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        val key = generateKey("prog_rsi", prices, period)
        return progressiveRsiCache.get(key) { IndicatorCalculator.calculateProgressiveRSI(prices, period) }
    }

    /**
     * Calculate ATR with caching.
     */
    fun calculateATR(candles: List<ru.driics.aitrade.domain.model.OkxCandleResponse>, period: Int): BigDecimal {
        val key = generateKey("atr", candles.mapNotNull { it.close.toBigDecimalOrNull() }, period)
        return atrCache.get(key) { IndicatorCalculator.calculateATR(candles, period) }
    }

    /**
     * Generate cache key from prices and parameters.
     * Uses hash of last N prices + period for efficient key generation.
     */
    private fun generateKey(prefix: String, prices: List<BigDecimal>, period: Int?): String {
        // Use last 50 prices for key generation to balance uniqueness and performance
        val relevantPrices = prices.takeLast(50)
        val priceString = relevantPrices.joinToString(",") { it.toPlainString() }
        val periodStr = period?.toString() ?: ""

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(priceString.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

        return "$prefix:${prices.size}:$periodStr:$hash"
    }

    /**
     * Clear all caches (useful for testing or manual invalidation).
     */
    fun clearAll() {
        emaCache.invalidateAll()
        macdCache.invalidateAll()
        rsiCache.invalidateAll()
        progressiveEmaCache.invalidateAll()
        progressiveMacdCache.invalidateAll()
        progressiveRsiCache.invalidateAll()
        atrCache.invalidateAll()
        log.info { "All indicator caches cleared" }
    }

    /**
     * Get cache statistics for monitoring.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "ema" to emaCache.stats(),
            "macd" to macdCache.stats(),
            "rsi" to rsiCache.stats(),
            "progressiveEma" to progressiveEmaCache.stats(),
            "progressiveMacd" to progressiveMacdCache.stats(),
            "progressiveRsi" to progressiveRsiCache.stats(),
            "atr" to atrCache.stats()
        )
    }
}


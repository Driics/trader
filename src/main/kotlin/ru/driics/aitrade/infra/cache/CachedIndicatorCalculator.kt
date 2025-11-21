package ru.driics.aitrade.infra.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.driics.aitrade.domain.model.OkxCandleResponse
import ru.driics.aitrade.domain.services.IndicatorCalculator
import java.math.BigDecimal
import java.time.Duration

/**
 * Cached indicator calculator using SmartCacheStrategy for unified metrics and write-through/promotion.
 * 
 * Replaces IndicatorCache to provide:
 * - Unified caching approach with SmartCacheStrategy
 * - Write-through pattern with L1/L3 promotion
 * - Metrics visible in Prometheus via Caffeine stats
 * - Consistent cache behavior across all data categories
 */
@Component
class CachedIndicatorCalculator(
    private val smartCache: SmartCacheStrategy
) {
    private val log = KotlinLogging.logger {}

    companion object {
        // Cache TTL: 5 minutes (same as old IndicatorCache)
        private val CACHE_TTL = Duration.ofMinutes(5)
    }

    /**
     * Calculate EMA with caching.
     */
    suspend fun calculateEMA(prices: List<BigDecimal>, period: Int): BigDecimal {
        val key = generateKey("indicator:ema", prices, period)
        return smartCache.getTyped(
            key = key,
            level = SmartCacheStrategy.CacheLevel.L3, // Use L3 for 1-hour TTL, promotes to L1
            fetcher = { IndicatorCalculator.calculateEMA(prices, period) },
            ttl = CACHE_TTL
        )
    }

    /**
     * Calculate MACD with caching.
     */
    suspend fun calculateMACD(prices: List<BigDecimal>): BigDecimal {
        val key = generateKey("indicator:macd", prices, null)
        return smartCache.getTyped(
            key = key,
            level = SmartCacheStrategy.CacheLevel.L3,
            fetcher = { IndicatorCalculator.calculateMACD(prices) },
            ttl = CACHE_TTL
        )
    }

    /**
     * Calculate RSI with caching.
     */
    suspend fun calculateRSI(prices: List<BigDecimal>, period: Int): BigDecimal {
        val key = generateKey("indicator:rsi", prices, period)
        return smartCache.getTyped(
            key = key,
            level = SmartCacheStrategy.CacheLevel.L3,
            fetcher = { IndicatorCalculator.calculateRSI(prices, period) },
            ttl = CACHE_TTL
        )
    }

    /**
     * Calculate progressive EMA with caching.
     */
    suspend fun calculateProgressiveEMA(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        val key = generateKey("indicator:prog_ema", prices, period)
        return smartCache.getTyped(
            key = key,
            level = SmartCacheStrategy.CacheLevel.L3,
            fetcher = { IndicatorCalculator.calculateProgressiveEMA(prices, period) },
            ttl = CACHE_TTL
        )
    }

    /**
     * Calculate progressive MACD with caching.
     */
    suspend fun calculateProgressiveMACD(prices: List<BigDecimal>): List<BigDecimal> {
        val key = generateKey("indicator:prog_macd", prices, null)
        return smartCache.getTyped(
            key = key,
            level = SmartCacheStrategy.CacheLevel.L3,
            fetcher = { IndicatorCalculator.calculateProgressiveMACD(prices) },
            ttl = CACHE_TTL
        )
    }

    /**
     * Calculate progressive RSI with caching.
     */
    suspend fun calculateProgressiveRSI(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        val key = generateKey("indicator:prog_rsi", prices, period)
        return smartCache.getTyped(
            key = key,
            level = SmartCacheStrategy.CacheLevel.L3,
            fetcher = { IndicatorCalculator.calculateProgressiveRSI(prices, period) },
            ttl = CACHE_TTL
        )
    }

    /**
     * Calculate ATR with caching.
     */
    suspend fun calculateATR(candles: List<OkxCandleResponse>, period: Int): BigDecimal {
        val prices = candles.mapNotNull { it.close.toBigDecimalOrNull() }
        val key = generateKey("indicator:atr", prices, period)
        return smartCache.getTyped(
            key = key,
            level = SmartCacheStrategy.CacheLevel.L3,
            fetcher = { IndicatorCalculator.calculateATR(candles, period) },
            ttl = CACHE_TTL
        )
    }

    /**
     * Generate cache key from prices and parameters.
     * Uses hash of last N prices + period for efficient key generation.
     * Same algorithm as old IndicatorCache for cache key compatibility.
     */
    private fun generateKey(prefix: String, prices: List<BigDecimal>, period: Int?): String {
        // Use last 50 prices for key generation to balance uniqueness and performance
        val relevantPrices = prices.takeLast(50)
        val priceHash = relevantPrices.joinToString(",") { it.toPlainString() }
        val periodStr = period?.toString() ?: ""
        return "$prefix:${prices.size}:$periodStr:${priceHash.hashCode()}"
    }

    /**
     * Clear all indicator caches (useful for testing or manual invalidation).
     * Invalidates all L3 cache entries with indicator prefix.
     */
    suspend fun clearAll() {
        // Note: SmartCacheStrategy doesn't support prefix-based invalidation
        // For now, we invalidate all L3 cache (which includes indicators)
        // In production, consider adding prefix-based invalidation to SmartCacheStrategy
        smartCache.invalidateAll(SmartCacheStrategy.CacheLevel.L3)
        log.info { "All indicator caches cleared (L3 invalidated)" }
    }
}


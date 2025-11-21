package ru.driics.aitrade.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import ru.driics.aitrade.infra.cache.SmartCacheStrategy

/**
 * Configuration to register Caffeine cache metrics with Micrometer for Prometheus exposure.
 * 
 * This ensures all cache metrics from SmartCacheStrategy are visible in Prometheus.
 * Metrics will be available at /actuator/prometheus with names like:
 * - cache_gets_total{cache="cache.smart.l1",result="hit"}
 * - cache_gets_total{cache="cache.smart.l1",result="miss"}
 * - cache_evictions_total{cache="cache.smart.l1"}
 * - cache_size{cache="cache.smart.l1"}
 * - cache_loads_total{cache="cache.smart.l1"}
 * - cache_load_duration_seconds{cache="cache.smart.l1"}
 */
@Configuration
class CacheMetricsConfig(
    private val meterRegistry: MeterRegistry,
    private val smartCacheStrategy: SmartCacheStrategy
) {
    @PostConstruct
    fun registerCacheMetrics() {
        registerCacheMetrics("cache.smart.l1", smartCacheStrategy.getL1Cache())
        registerCacheMetrics("cache.smart.l3", smartCacheStrategy.getL3Cache())
    }

    private fun registerCacheMetrics(cacheName: String, cache: com.github.benmanes.caffeine.cache.Cache<*, *>) {
        val tags = listOf(Tag.of("cache", cacheName))
        
        // Cache size
        Gauge.builder("cache.size", cache) { it.estimatedSize().toDouble() }
            .tags(tags)
            .description("The number of entries in the cache")
            .register(meterRegistry)
        
        // Cache stats (hit rate, miss rate, etc.)
        Gauge.builder("cache.gets", cache) { it.stats().hitCount().toDouble() }
            .tags(tags + Tag.of("result", "hit"))
            .description("The number of cache hits")
            .register(meterRegistry)
        
        Gauge.builder("cache.gets", cache) { it.stats().missCount().toDouble() }
            .tags(tags + Tag.of("result", "miss"))
            .description("The number of cache misses")
            .register(meterRegistry)
        
        Gauge.builder("cache.evictions", cache) { it.stats().evictionCount().toDouble() }
            .tags(tags)
            .description("The number of cache evictions")
            .register(meterRegistry)
        
        Gauge.builder("cache.loads", cache) { it.stats().loadCount().toDouble() }
            .tags(tags)
            .description("The number of times the cache loader was called")
            .register(meterRegistry)
        
        Gauge.builder("cache.load.duration", cache) { 
            it.stats().totalLoadTime() / 1_000_000_000.0 // Convert nanoseconds to seconds
        }
            .tags(tags)
            .description("The total time spent loading new values in seconds")
            .register(meterRegistry)
        
        // Cache hit rate: hits / (hits + misses)
        Gauge.builder("cache.hit_rate", cache) {
            val stats = it.stats()
            val hits = stats.hitCount()
            val misses = stats.missCount()
            val total = hits + misses
            if (total > 0) {
                hits.toDouble() / total.toDouble()
            } else {
                0.0
            }
        }
            .tags(tags)
            .description("Cache hit rate (hits / (hits + misses))")
            .register(meterRegistry)
    }
}


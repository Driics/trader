package ru.driics.aitrade.infra.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.types.asInstrumentId
import ru.driics.aitrade.infra.exchange.OkxExchangeAdapter

/**
 * Service to warm up caches at application startup.
 * Pre-loads frequently accessed data to improve initial response times.
 */
@Component
@ConditionalOnProperty(name = ["cache.warming.enabled"], havingValue = "true", matchIfMissing = true)
class CacheWarmingService(
    private val exchangeAdapter: OkxExchangeAdapter,
    private val tradingProperties: TradingProperties,
    private val smartCache: SmartCacheStrategy
) {
    private val log = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostConstruct
    fun warmUpCaches() {
        scope.launch {
            try {
                log.info { "Starting cache warming..." }
                warmInstrumentCache()
                log.info { "Cache warming completed successfully" }
            } catch (e: Exception) {
                log.error(e) { "Cache warming failed" }
            }
        }
    }

    /**
     * Warm instrument cache by pre-loading instrument info for all trading symbols.
     */
    private suspend fun warmInstrumentCache() = coroutineScope {
        val symbols = tradingProperties.getCurrenciesList()
        log.info { "Warming instrument cache for ${symbols.size} symbols" }

        val jobs = symbols.map { symbol ->
            async {
                try {
                    val instId = "${symbol}-USDT-SWAP"
                    val instrumentId = instId.asInstrumentId()
                    
                    // Use read-through pattern - will fetch and cache if not present
                    exchangeAdapter.loadInstrument(instrumentId)
                    
                    log.debug { "Warmed cache for instrument: $instId" }
                } catch (e: Exception) {
                    log.warn(e) { "Failed to warm cache for symbol: $symbol" }
                }
            }
        }

        jobs.awaitAll()
        log.info { "Instrument cache warming completed for ${symbols.size} symbols" }
    }

    /**
     * Manually trigger cache warming (useful for admin endpoints).
     */
    suspend fun warmUpNow() {
        log.info { "Manual cache warming triggered" }
        warmInstrumentCache()
    }
}


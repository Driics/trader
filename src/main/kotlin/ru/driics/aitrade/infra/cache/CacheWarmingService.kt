package ru.driics.aitrade.infra.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.infra.exchange.OkxExchangeAdapter
import kotlin.time.measureTime

/**
 * Service to warm up caches at application startup.
 * Pre-loads frequently accessed data to improve initial response times.
 */
@Component
@ConditionalOnProperty(name = ["cache.warming.enabled"], havingValue = "true", matchIfMissing = true)
class CacheWarmingService(
    private val exchangeAdapter: OkxExchangeAdapter,
    private val tradingProperties: TradingProperties,
    private val instrumentResolver: InstrumentResolver,
) {
    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostConstruct
    fun scheduleWarmUp() {
        scope.launch {
            warmUpInternal()
        }
    }

    @PreDestroy
    fun destroy() {
        scope.cancel()
    }

    /**
     * Manually trigger cache warming (useful for admin endpoints).
     */
    suspend fun warmUpNow() {
        log.info { "Manual cache warming triggered" }
        warmUpInternal()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun warmUpInternal() {
        val symbols = tradingProperties.getCurrenciesList()
        if (symbols.isEmpty()) {
            log.warn { "No symbols configured for cache warming" }
            return
        }

        log.info { "Starting cache warming for ${symbols.size} symbols..." }

        val duration = measureTime {
            // Use Flow to manage concurrency and backpressure
            symbols.asFlow()
                .flatMapMerge(concurrency = tradingProperties.maxConcurrentSymbols) { symbol ->
                    flow {
                        emit(loadInstrumentSafe(symbol))
                    }
                }
                .collect() // Wait for all to finish
        }

        log.info { "Cache warming completed in $duration" }
    }

    private suspend fun loadInstrumentSafe(symbol: String) {
        val instId = instrumentResolver.instrumentId(symbol)
        try {
            // Read-through pattern: calling load will fetch from API and put into SmartCache
            exchangeAdapter.loadInstrument(instId)
            log.debug { "Warmed: ${instId.value}" }
        } catch (e: Exception) {
            // Log warning but don't stop the warming process for other symbols
            log.warn { "Failed to warm cache for ${instId.value}: ${e.message}" }
        }
    }
}
package ru.driics.aitrade.infra.exchange.adapter

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.OrderEvent
import ru.driics.aitrade.domain.ports.PositionEvent
import ru.driics.aitrade.domain.ports.PriceUpdate
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.infra.exchange.websocket.OkxPrivateWebSocketClient
import ru.driics.aitrade.infra.exchange.websocket.OkxPublicWebSocketClient
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsOrderUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsPositionUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTickerUpdate
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class OkxStreamingAdapter(
    private val publicWs: OkxPublicWebSocketClient,
    private val privateWs: OkxPrivateWebSocketClient,
    private val tradingProperties: TradingProperties
) : StreamingMarketDataPort {

    private val log = KotlinLogging.logger {}

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error(throwable) { "Uncaught exception in streaming coroutine" }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + exceptionHandler
    )

    private val lastPriceCache = ConcurrentHashMap<String, BigDecimal>()

    // Shared flow: map once, filter per subscriber
    private val allPriceUpdates: SharedFlow<PriceUpdate> by lazy {
        publicWs.tickerFlow
            .mapNotNull { it.toDomain() }
            .shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
                replay = 0
            )
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @PostConstruct
    fun init() {
        val instruments = tradingProperties.getCurrenciesList()

        if (instruments.isEmpty()) {
            log.warn { "No instruments configured for market data subscription" }
            return
        }

        log.info { "Initializing OKX streaming for ${instruments.size} instruments" }

        subscribeToInstruments(instruments)
        startPriceCacheMaintenance()
    }

    @PreDestroy
    fun stop() {
        log.info { "Shutting down OKX streaming adapter" }
        scope.cancel("Application shutdown")
    }

    // =========================================================================
    // Port Implementation
    // =========================================================================

    override fun getRealtimePrice(instId: String): BigDecimal? =
        lastPriceCache[instId]

    override fun observePriceUpdates(instId: String): Flow<PriceUpdate> =
        allPriceUpdates.filter { it.instId == instId }

    override fun observeOrderUpdates(): Flow<OrderEvent> =
        privateWs.orderFlow.mapNotNull { it.toDomain() }

    override fun observePositionUpdates(): Flow<PositionEvent> =
        privateWs.positionFlow.mapNotNull { it.toDomain() }

    // =========================================================================
    // Subscription Management
    // =========================================================================

    private fun subscribeToInstruments(symbols: List<String>) {
        scope.launch {
            symbols.forEach { symbol ->
                subscribeToInstrument(symbol.toInstId())
            }
        }
    }

    private suspend fun subscribeToInstrument(instId: String) {
        runCatching {
            publicWs.subscribeTicker(instId)
            Timeframe.ALL.forEach { tf ->
                publicWs.subscribeCandles(instId, tf)
            }
        }.onSuccess {
            log.debug { "Subscribed to $instId" }
        }.onFailure { e ->
            log.error(e) { "Failed to subscribe to $instId" }
        }
    }

    private fun startPriceCacheMaintenance() {
        scope.launch {
            publicWs.tickerFlow
                .mapNotNull { tick ->
                    tick.last.toBigDecimalOrNull()?.let { tick.instId to it }
                }
                .collect { (instId, price) ->
                    lastPriceCache[instId] = price
                }
        }
    }

    // =========================================================================
    // Domain Mappers
    // =========================================================================

    private fun OkxWsTickerUpdate.toDomain(): PriceUpdate? {
        return PriceUpdate(
            instId = instId,
            price = last.parseBigDecimal("ticker.last") ?: return null,
            timestamp = ts.parseEpochMillis("ticker.ts") ?: return null
        )
    }

    private fun OkxWsOrderUpdate.toDomain(): OrderEvent? {
        return OrderEvent(
            instId = instId,
            orderId = ordId,
            clOrdId = clOrdId,
            state = state,
            side = side,
            avgPx = avgPx?.toBigDecimalOrNull(),
            timestamp = ts.parseEpochMillis("order.ts") ?: return null
        )
    }

    private fun OkxWsPositionUpdate.toDomain(): PositionEvent? {
        return PositionEvent(
            instId = instId,
            pos = pos.parseBigDecimal("position.pos") ?: return null,
            avgPx = avgPx.parseBigDecimal("position.avgPx") ?: return null,
            upl = upl.parseBigDecimal("position.upl") ?: return null,
            timestamp = ts.parseEpochMillis("position.ts") ?: return null
        )
    }

    // =========================================================================
    // Parsing Extensions
    // =========================================================================

    private fun String?.parseBigDecimal(field: String): BigDecimal? {
        if (isNullOrBlank()) return null
        return runCatching { BigDecimal(this) }
            .onFailure { log.warn { "Invalid $field: '$this'" } }
            .getOrNull()
    }

    private fun String?.parseEpochMillis(field: String): Instant? {
        val millis = this?.toLongOrNull()
        if (millis == null) {
            if (!isNullOrBlank()) log.debug { "Invalid $field: '$this'" }
            return null
        }
        return Instant.ofEpochMilli(millis)
    }

    private fun String.toInstId(): String = "${this}${INSTRUMENT_SUFFIX}"

    // =========================================================================
    // Constants
    // =========================================================================

    private companion object {
        const val INSTRUMENT_SUFFIX = "-USDT-SWAP"
    }

    private object Timeframe {
        const val M1 = "1m"
        const val H4 = "4H"
        val ALL = listOf(M1, H4)
    }
}
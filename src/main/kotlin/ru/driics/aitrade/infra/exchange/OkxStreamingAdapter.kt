package ru.driics.aitrade.infra.exchange

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
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

    // Use Default dispatcher for mapping/calculations, but consider IO if downstream is blocking
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Thread-safe cache for synchronous access
    private val lastPriceCache = ConcurrentHashMap<String, BigDecimal>()

    private companion object {
        const val INSTRUMENT_SUFFIX = "-USDT-SWAP"
        const val TIMEFRAME_1M = "1m"
        const val TIMEFRAME_4H = "4H"
    }

    @PostConstruct
    fun init() {
        // 1. Configure Subscriptions
        // The WS clients will handle queuing these and sending them when the connection is ready.
        tradingProperties.getCurrenciesList().forEach { symbol ->
            val instId = symbol.toInstId()
            runCatching {
                scope.launch {
                    publicWs.subscribeTicker(instId)
                    publicWs.subscribeCandles(instId, TIMEFRAME_1M)
                    publicWs.subscribeCandles(instId, TIMEFRAME_4H)
                }
            }.onFailure { e ->
                log.error(e) { "Failed to queue subscriptions for $instId" }
            }
        }

        // 2. Start internal cache maintenance
        scope.launch {
            publicWs.tickerFlow.collect { tick ->
                val price = tick.last.toBigDecimalOrNull()
                if (price != null) {
                    lastPriceCache[tick.instId] = price
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    // =========================================================================
    // Port Implementation
    // =========================================================================

    override fun getRealtimePrice(instId: String): BigDecimal? = lastPriceCache[instId]

    override fun observePriceUpdates(instId: String): Flow<PriceUpdate> =
        publicWs.tickerFlow
            .filter { it.instId == instId }
            .mapNotNull { it.toDomain() }

    override fun observeOrderUpdates(): Flow<OrderEvent> =
        privateWs.orderFlow
            .mapNotNull { it.toDomain() }

    override fun observePositionUpdates(): Flow<PositionEvent> =
        privateWs.positionFlow
            .mapNotNull { it.toDomain() }

    // =========================================================================
    // Mappers & Extensions
    // =========================================================================

    private fun String.toInstId() = "$this$INSTRUMENT_SUFFIX"

    private fun OkxWsTickerUpdate.toDomain(): PriceUpdate? {
        val price = this.last.parseBigDecimal("ticker price") ?: return null
        val timestamp = this.ts.parseInstant("ticker ts") ?: return null

        return PriceUpdate(
            instId = this.instId,
            price = price,
            timestamp = timestamp
        )
    }

    private fun OkxWsOrderUpdate.toDomain(): OrderEvent? {
        // Required fields
        val timestamp = this.ts.parseInstant("order ts") ?: return null

        return OrderEvent(
            instId = this.instId,
            orderId = this.ordId,
            clOrdId = this.clOrdId,
            state = this.state,
            side = this.side,
            // Optional/Nullable fields in domain
            avgPx = this.avgPx?.toBigDecimalOrNull(),
            timestamp = timestamp
        )
    }

    private fun OkxWsPositionUpdate.toDomain(): PositionEvent? {
        val pos = this.pos.parseBigDecimal("pos size") ?: return null
        val avgPx = this.avgPx.parseBigDecimal("pos avgPx") ?: return null
        val upl = this.upl.parseBigDecimal("pos upl") ?: return null
        val timestamp = this.ts.parseInstant("pos ts") ?: return null

        return PositionEvent(
            instId = this.instId,
            pos = pos,
            avgPx = avgPx,
            upl = upl,
            timestamp = timestamp
        )
    }

    /**
     * Helper to parse BigDecimal with consistent error logging
     */
    private fun String?.parseBigDecimal(fieldName: String): BigDecimal? {
        if (this.isNullOrBlank()) return null
        return try {
            BigDecimal(this)
        } catch (e: NumberFormatException) {
            log.warn(e) { "Invalid $fieldName format: '$this'" }
            null
        }
    }

    /**
     * Helper to parse Epoch Millis String to Instant
     */
    private fun String?.parseInstant(fieldName: String): Instant? {
        val longVal = this?.toLongOrNull()
        if (longVal == null) {
            log.debug { "Missing or invalid $fieldName: '$this'" }
            return null
        }
        return Instant.ofEpochMilli(longVal)
    }
}
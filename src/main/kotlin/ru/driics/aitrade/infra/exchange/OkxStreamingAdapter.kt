package ru.driics.aitrade.infra.exchange

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.OrderEvent
import ru.driics.aitrade.domain.ports.PositionEvent
import ru.driics.aitrade.domain.ports.PriceUpdate
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.infra.exchange.websocket.OkxPrivateWebSocketClient
import ru.driics.aitrade.infra.exchange.websocket.OkxPublicWebSocketClient
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastPrice = ConcurrentHashMap<String, BigDecimal>()

    @PostConstruct
    fun start() {
        scope.launch {
            launch { publicWs.connect() }
            delay(1_500)
            launch { privateWs.connect() }

            // Subscribe to configured symbols (tickers + a couple of candles)
            tradingProperties.getCurrenciesList().forEach { symbol ->
                val instId = "$symbol-USDT-SWAP"
                runCatching {
                    publicWs.subscribeTicker(instId)
                    publicWs.subscribeCandles(instId, "1m")
                    publicWs.subscribeCandles(instId, "4H")
                }.onFailure { e -> log.warn(e) { "WS subscribe failed for $instId" } }
            }

            // Maintain last price cache
            launch {
                publicWs.tickerFlow.collect { m ->
                    val instId = m.instId
                    val last = m.last.toBigDecimalOrNull() ?: return@collect
                    lastPrice[instId] = last
                }
            }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()

    override fun getRealtimePrice(instId: String): BigDecimal? = lastPrice[instId]

    override fun observePriceUpdates(instId: String): Flow<PriceUpdate> =
        publicWs.tickerFlow
            .filter { it.instId == instId }
            .map {
                val last = it.last
                val price = last.toBigDecimalOrNull() ?: run {
                    log.warn { "Invalid price format in ticker: $last" }
                    return@map null
                }
                val ts = it.ts.toLongOrNull() ?: run {
                    log.warn { "Missing timestamp in ticker for $instId" }
                    return@map null
                }
                PriceUpdate(instId = instId, price = price, timestamp = Instant.ofEpochMilli(ts))
            }.filterNotNull()

    override fun observeOrderUpdates(): Flow<OrderEvent> =
        privateWs.orderFlow.map {
            val instId = it.instId
            val ordId = it.ordId
            val clOrdId = it.clOrdId
            val state = it.state
            val side = it.side
            val avgPx = it.avgPx?.toBigDecimalOrNull()
            val ts = it.ts.toLongOrNull() ?: run {
                log.warn { "Missing timestamp in order event for $instId" }
                return@map null
            }
            OrderEvent(instId, ordId, clOrdId, state, side, avgPx, Instant.ofEpochMilli(ts))
        }.filterNotNull()

    override fun observePositionUpdates(): Flow<PositionEvent> =
        privateWs.positionFlow.map {
            val instId = it.instId
            val posStr = it.pos
            val pos = posStr.toBigDecimalOrNull() ?: return@map null
            val avgPxStr = it.avgPx
            val avgPx = avgPxStr.toBigDecimalOrNull() ?: return@map null
            val uplStr = it.upl
            val upl = uplStr.toBigDecimalOrNull() ?: return@map null
            val ts = it.ts.toLongOrNull() ?: run {
                log.warn { "Missing timestamp in position event for $instId" }
                return@map null
            }
            PositionEvent(instId, pos, avgPx, upl, Instant.ofEpochMilli(ts))
        }.filterNotNull()
}
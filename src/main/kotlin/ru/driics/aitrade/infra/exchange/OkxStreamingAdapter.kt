package ru.driics.aitrade.infra.exchange

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.*
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
                    val instId = (m["instId"] as? String) ?: return@collect
                    val last = (m["last"] as? String)?.toBigDecimalOrNull() ?: return@collect
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
            .filter { it["instId"] == instId }
            .map {
                val price = (it["last"] as String).toBigDecimal()
                val ts = (it["ts"] as? String)?.toLongOrNull() ?: System.currentTimeMillis()
                PriceUpdate(instId = instId, price = price, timestamp = Instant.ofEpochMilli(ts))
            }

    override fun observeOrderUpdates(): Flow<OrderEvent> =
        privateWs.orderFlow.map {
            val instId = it["instId"] as String
            val ordId = it["ordId"] as String
            val clOrdId = it["clOrdId"] as? String
            val state = it["state"] as String
            val side = it["side"] as String
            val avgPx = (it["avgPx"] as? String)?.toBigDecimalOrNull()
            val ts = (it["ts"] as? String)?.toLongOrNull() ?: 0L
            OrderEvent(instId, ordId, clOrdId, state, side, avgPx, Instant.ofEpochMilli(ts))
        }

    override fun observePositionUpdates(): Flow<PositionEvent> =
        privateWs.positionFlow.map {
            val instId = it["instId"] as String
            val pos = (it["pos"] as String).toBigDecimalOrNull() ?: BigDecimal.ZERO
            val avgPx = (it["avgPx"] as String).toBigDecimalOrNull() ?: BigDecimal.ZERO
            val upl = (it["upl"] as String).toBigDecimalOrNull() ?: BigDecimal.ZERO
            val ts = (it["ts"] as? String)?.toLongOrNull() ?: 0L
            PositionEvent(instId, pos, avgPx, upl, Instant.ofEpochMilli(ts))
        }
}
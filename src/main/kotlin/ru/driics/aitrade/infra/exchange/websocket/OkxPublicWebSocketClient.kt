package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsCandleUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTickerUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTypeRefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a subscription to an OKX public channel.
 */
data class ChannelSubscription(
    val channel: String,
    val instId: String? = null,
    val instType: String? = null
) {
    fun toArgs(): Map<String, String> = buildMap {
        put("channel", channel)
        instId?.let { put("instId", it) }
        instType?.let { put("instType", it) }
    }
}

@Component
class OkxPublicWebSocketClient(
    httpClient: HttpClient,
    objectMapper: ObjectMapper,
    private val okxProperties: OkxProperties,
) : OkxBaseWebSocketClient(httpClient, objectMapper) {

    override val log: KLogger = KotlinLogging.logger {}
    override val wsUrl: String get() = okxProperties.publicWsUrl()

    // Thread-safe subscription registry
    private val subscriptions = ConcurrentHashMap.newKeySet<ChannelSubscription>()
    private val subscriptionMutex = Mutex()

    // Flows
    private val _tickerFlowMutable = FlowFactory.highThroughput<OkxWsTickerUpdate>(1024).first
    val tickerFlow: SharedFlow<OkxWsTickerUpdate> = _tickerFlowMutable.asSharedFlow()

    private val _candleFlowMutable = FlowFactory.highThroughput<CandleEvent>(512).first
    val candleFlow: SharedFlow<CandleEvent> = _candleFlowMutable.asSharedFlow()

    /**
     * Wrapper for candle updates with instrument context.
     */
    data class CandleEvent(
        val instId: String,
        val period: String,
        val candle: OkxWsCandleUpdate
    )

    private companion object Channels {
        const val TICKERS = "tickers"
        const val CANDLE_PREFIX = "candle"
    }

    @PostConstruct
    override fun start() = super.start()

    @PreDestroy
    override fun stop() = super.stop()

    // =========================================================================
    // Session Lifecycle
    // =========================================================================

    override suspend fun onSessionEstablished(session: DefaultClientWebSocketSession): Boolean {
        resubscribeAll()
        return true
    }

    private suspend fun resubscribeAll() {
        val currentSubs = subscriptions.toList()
        if (currentSubs.isEmpty()) return

        log.info { "Restoring ${currentSubs.size} subscriptions" }
        sendSubscribeRequest(currentSubs)
    }

    // =========================================================================
    // Subscription API
    // =========================================================================

    /**
     * Subscribe to ticker updates for an instrument.
     */
    suspend fun subscribeTicker(instId: String) {
        addSubscription(ChannelSubscription(channel = TICKERS, instId = instId))
    }

    /**
     * Subscribe to candlestick updates.
     * @param instId Instrument ID (e.g., "BTC-USDT-SWAP")
     * @param period Candlestick period (e.g., "1m", "5m", "1H", "1D")
     */
    suspend fun subscribeCandles(instId: String, period: String) {
        addSubscription(ChannelSubscription(channel = "$CANDLE_PREFIX$period", instId = instId))
    }

    /**
     * Unsubscribe from ticker updates.
     */
    suspend fun unsubscribeTicker(instId: String) {
        removeSubscription(ChannelSubscription(channel = TICKERS, instId = instId))
    }

    /**
     * Unsubscribe from candlestick updates.
     */
    suspend fun unsubscribeCandles(instId: String, period: String) {
        removeSubscription(ChannelSubscription(channel = "$CANDLE_PREFIX$period", instId = instId))
    }

    private suspend fun addSubscription(sub: ChannelSubscription) {
        subscriptionMutex.withLock {
            if (subscriptions.add(sub)) {
                if (isConnected) {
                    sendSubscribeRequest(listOf(sub))
                }
            }
        }
    }

    private suspend fun removeSubscription(sub: ChannelSubscription) {
        subscriptionMutex.withLock {
            if (subscriptions.remove(sub)) {
                if (isConnected) {
                    sendUnsubscribeRequest(listOf(sub))
                }
            }
        }
    }

    private suspend fun sendSubscribeRequest(subs: List<ChannelSubscription>) {
        val payload = mapOf(
            "op" to "subscribe",
            "args" to subs.map { it.toArgs() }
        )
        sendMessage(payload)
    }

    private suspend fun sendUnsubscribeRequest(subs: List<ChannelSubscription>) {
        val payload = mapOf(
            "op" to "unsubscribe",
            "args" to subs.map { it.toArgs() }
        )
        sendMessage(payload)
    }

    // =========================================================================
    // Message Dispatching
    // =========================================================================

    override fun dispatchMessage(node: JsonNode) {
        if (node.has("event")) {
            handleEventMessage(node)
            return
        }

        if (!node.has("data")) return

        val arg = node.path("arg")
        val channel = arg.path("channel").asText()
        val instId = arg.path("instId").asText()

        dispatchDataUpdate(channel, instId, node)
    }

    private fun dispatchDataUpdate(channel: String, instId: String, node: JsonNode) {
        runCatching {
            when {
                channel == TICKERS -> {
                    objectMapper.treeToValue(node, OkxWsTypeRefs.ticker)
                        .data
                        ?.forEach { _tickerFlowMutable.tryEmit(it) }
                }

                channel.startsWith(CANDLE_PREFIX) -> {
                    val period = channel.removePrefix(CANDLE_PREFIX)
                    objectMapper.treeToValue(node, OkxWsTypeRefs.candle)
                        .data
                        ?.filter { instId.isNotEmpty() }
                        ?.forEach {
                            _candleFlowMutable.tryEmit(CandleEvent(instId, period, it))
                        }
                }

                else -> log.trace { "Unhandled channel: $channel" }
            }
        }.onFailure { e ->
            log.warn(e) { "Failed to parse $channel update" }
        }
    }

    private fun handleEventMessage(node: JsonNode) {
        when (val event = node.path("event").asText()) {
            "subscribe" -> log.debug { "Subscribed: ${node.path("arg")}" }
            "unsubscribe" -> log.debug { "Unsubscribed: ${node.path("arg")}" }
            "error" -> log.error { "Error: ${node.path("msg").asText()} (${node.path("code")})" }
        }
    }
}
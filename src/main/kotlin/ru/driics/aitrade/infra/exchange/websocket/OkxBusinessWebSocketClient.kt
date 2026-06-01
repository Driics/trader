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
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsCandleUpdate
import java.util.concurrent.ConcurrentHashMap

/**
 * OKX "business" WebSocket client (`/ws/v5/business`). Candlestick channels (`candle1m`, `candle4H`, …)
 * live ONLY on this endpoint — subscribing to them on the public socket (`/ws/v5/public`) is rejected
 * with error 60018 ("Wrong URL or channel … doesn't exist"). This is the candle counterpart to
 * [OkxPublicWebSocketClient] (tickers); they share [ChannelSubscription] and the base connection
 * lifecycle in [OkxBaseWebSocketClient].
 */
@Component
class OkxBusinessWebSocketClient(
    httpClient: HttpClient,
    objectMapper: ObjectMapper,
    private val okxProperties: OkxProperties,
) : OkxBaseWebSocketClient(httpClient, objectMapper) {

    override val log: KLogger = KotlinLogging.logger {}
    override val wsUrl: String get() = okxProperties.businessWsUrl()

    // Thread-safe subscription registry (mirrors the public client): add()/remove() are atomic and
    // return true only for the first caller, so the subscribe/unsubscribe gate is race-free and OKX
    // is idempotent on re-subscribe.
    private val subscriptions = ConcurrentHashMap.newKeySet<ChannelSubscription>()

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
     * Subscribe to candlestick updates.
     * @param instId Instrument ID (e.g., "BTC-USDT-SWAP")
     * @param period Candlestick period (e.g., "1m", "5m", "1H", "1D")
     */
    suspend fun subscribeCandles(instId: String, period: String) {
        addSubscription(ChannelSubscription(channel = "$CANDLE_PREFIX$period", instId = instId))
    }

    /**
     * Unsubscribe from candlestick updates.
     */
    suspend fun unsubscribeCandles(instId: String, period: String) {
        removeSubscription(ChannelSubscription(channel = "$CANDLE_PREFIX$period", instId = instId))
    }

    private suspend fun addSubscription(sub: ChannelSubscription) {
        if (subscriptions.add(sub) && isConnected) {
            sendSubscribeRequest(listOf(sub))
        }
    }

    private suspend fun removeSubscription(sub: ChannelSubscription) {
        if (subscriptions.remove(sub) && isConnected) {
            sendUnsubscribeRequest(listOf(sub))
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
                channel.startsWith(CANDLE_PREFIX) -> {
                    // Guard once on the batch's instId (it comes from the envelope arg, not each row).
                    if (instId.isEmpty()) return@runCatching
                    val period = channel.removePrefix(CANDLE_PREFIX)
                    // OKX candle rows are ARRAYS ([ts,o,h,l,c,...]), not objects — bind each row
                    // directly instead of treeToValue into an object envelope (which never matched).
                    val rows = node.path("data")
                    if (rows.isArray) {
                        for (row in rows) {
                            OkxWsCandleUpdate.fromArray(row.map { it.asText() })?.let {
                                _candleFlowMutable.tryEmit(CandleEvent(instId, period, it))
                            }
                        }
                    }
                }

                else -> log.trace { "Unhandled channel: $channel" }
            }
        }.onFailure { e ->
            log.warn(e) { "Failed to parse $channel update" }
        }
    }

    private fun handleEventMessage(node: JsonNode) {
        when (node.path("event").asText()) {
            "subscribe" -> log.debug { "Subscribed: ${node.path("arg")}" }
            "unsubscribe" -> log.debug { "Unsubscribed: ${node.path("arg")}" }
            // Keep this ERROR branch: a recurring 60018 ("channel doesn't exist") is the signal that a
            // candle subscription is hitting the wrong endpoint — exactly the bug this client fixes.
            "error" -> log.error { "Error: ${node.path("msg").asText()} (${node.path("code")})" }
        }
    }
}

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

    // Thread-safe subscription registry. A concurrent set is enough: add()/remove() are atomic and
    // return true only for the first caller, so the subscribe/unsubscribe gate is race-free without a
    // separate mutex (and OKX is idempotent on re-subscribe).
    private val subscriptions = ConcurrentHashMap.newKeySet<ChannelSubscription>()

    // Flows
    private val _tickerFlowMutable = FlowFactory.highThroughput<OkxWsTickerUpdate>(1024).first
    val tickerFlow: SharedFlow<OkxWsTickerUpdate> = _tickerFlowMutable.asSharedFlow()

    private companion object Channels {
        const val TICKERS = "tickers"
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
     * Unsubscribe from ticker updates.
     */
    suspend fun unsubscribeTicker(instId: String) {
        removeSubscription(ChannelSubscription(channel = TICKERS, instId = instId))
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

        val channel = node.path("arg").path("channel").asText()
        dispatchDataUpdate(channel, node)
    }

    private fun dispatchDataUpdate(channel: String, node: JsonNode) {
        runCatching {
            when (channel) {
                TICKERS -> {
                    objectMapper.treeToValue(node, OkxWsTypeRefs.ticker)
                        .data
                        ?.forEach { _tickerFlowMutable.tryEmit(it) }
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
            "error" -> log.error { "Error: ${node.path("msg").asText()} (${node.path("code")})" }
        }
    }
}
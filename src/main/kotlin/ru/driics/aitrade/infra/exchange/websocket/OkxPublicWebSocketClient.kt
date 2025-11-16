package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.infra.exchange.websocket.dto.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import jakarta.annotation.PreDestroy
import kotlin.math.min
import kotlin.random.Random


/**
 * Type-safe OKX public WebSocket client.
 *
 * - One reader on session.incoming
 * - Explicit wss URL (paper/live) via OkxProperties.publicWsUrl()
 * - Subscriptions are re-sent on reconnect
 * - Backoff with jitter
 * - Emits typed DTOs
 */
@Component
class OkxPublicWebSocketClient(
    private val httpClient: HttpClient,
    private val okxProperties: OkxProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = KotlinLogging.logger {}

    // Typed hot streams
    private val _tickerFlow = MutableSharedFlow<OkxWsTickerUpdate>(
        replay = 1, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tickerFlow: SharedFlow<OkxWsTickerUpdate> = _tickerFlow.asSharedFlow()

    private val _candleFlow = MutableSharedFlow<Pair<String /*instId*/, OkxWsCandleUpdate>>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val candleFlow: SharedFlow<Pair<String, OkxWsCandleUpdate>> = _candleFlow.asSharedFlow()

    // Subscription registries
    private val tickerSubs = ConcurrentHashMap.newKeySet<String>()
    private val candleSubs = ConcurrentHashMap.newKeySet<Pair<String, String>>() // (instId, period)

    private val connected = AtomicBoolean(false)
    private val activeSession = AtomicReference<DefaultClientWebSocketSession?>(null)
    private val stopping = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun connect() {
        var attempt = 0
        while (scope.isActive && !stopping.get()) {
            try {
                val url = okxProperties.publicWsUrl()
                httpClient.webSocket(urlString = url, request = {
                    headers.append("User-Agent", "AiTrader/1.0 (+okx ws)")
                    headers.append("Accept", "application/json")
                    headers.append("Origin", "https://www.okx.com")
                }) {
                    connected.set(true)
                    activeSession.set(this)
                    log.info { "Connected to OKX PUBLIC WS: $url" }
                    attempt = 0 // reset backoff on success

                    // Resubscribe in the same session context (single sender)
                    resubscribeAll(this)

                    // Ping
                    val pingJob = launch {
                        while (isActive) {
                            try { send(Frame.Text("ping")) } catch (_: Throwable) { break }
                            delay(15_000)
                        }
                    }

                    // Single reader
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> handleText(frame.readText())
                                is Frame.Ping -> {
                                    try {
                                        send(Frame.Pong(frame.data))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        log.warn(e) { "Pong wasn't send" }
                                        break
                                    }
                                }
                                is Frame.Close -> {
                                    closeReason.await()?.let {
                                        log.warn { "Public WS close: $it" }
                                    }
                                    break
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        pingJob.cancel()
                        connected.set(false)
                        activeSession.set(null)
                        log.warn { "Public WS disconnected" }
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (!scope.isActive || stopping.get()) {
                    log.info { "Public WS stopping, not reconnecting" }
                    break
                }
                // Only log reconnect message if not stopping
                if (!stopping.get()) {
                    val base = min(30_000, (1_000 shl attempt))
                    val sleep = base + Random.nextInt(0, 1_000)
                    log.warn(t) { "Public WS reconnect in ${sleep}ms (attempt=$attempt)" }
                    delay(sleep.toLong())
                }
                attempt = (attempt + 1).coerceAtMost(15)
            }
        }
    }

    suspend fun subscribeTicker(instId: String) {
        tickerSubs += instId
        activeSession.get()?.let { session ->
            val payload = mapOf("op" to "subscribe", "args" to listOf(mapOf("channel" to "tickers", "instId" to instId)))
            session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
        }
    }

    suspend fun subscribeCandles(instId: String, period: String) {
        candleSubs += (instId to period)
        activeSession.get()?.let { session ->
            val payload = mapOf("op" to "subscribe", "args" to listOf(mapOf("channel" to "candle$period", "instId" to instId)))
            session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
        }
    }

    private suspend fun resubscribeAll(session: DefaultClientWebSocketSession) {
        // tickers
        for (instId in tickerSubs) {
            val payload = mapOf("op" to "subscribe", "args" to listOf(mapOf("channel" to "tickers", "instId" to instId)))
            session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
        }
        // candles
        for ((instId, period) in candleSubs) {
            val payload = mapOf("op" to "subscribe", "args" to listOf(mapOf("channel" to "candle$period", "instId" to instId)))
            session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
        }
    }

    private fun handleText(text: String) {
        try {
            // Quick skim to route by channel without heavy mapping
            val root: Map<String, Any?> = objectMapper.readValue(text)
            val event = root["event"] as? String
            if (event == "subscribe" || event == "error" || event == "login") {
                // Acks or errors; leave at debug
                return
            }
            val arg = root["arg"] as? Map<*, *> ?: return
            val channel = arg["channel"] as? String ?: return
            val instId = arg["instId"] as? String

            when {
                channel == "tickers" -> {
                    val env = objectMapper.readValue(text, OkxWsTypeRefs.ticker)
                    env.data?.forEach { _tickerFlow.tryEmit(it) }
                }
                channel.startsWith("candle") -> {
                    // Candle payload can be object-shaped (supported here). If you see array form, see CandleArrayUtil below.
                    val env = objectMapper.readValue(text, OkxWsTypeRefs.candle)
                    env.data?.forEach { c ->
                        if (instId != null) _candleFlow.tryEmit(instId to c)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug(e) { "Public WS parse failed: ${text.take(200)}" }
        }
    }

    @PreDestroy
    fun destroy() {
        stopping.set(true)
        scope.cancel()
        activeSession.get()?.let { session ->
            try {
                runBlocking {
                    withTimeoutOrNull(5_000) {
                        session.close()
                    } ?: log.warn { "Public WS session close timed out" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Error closing public WS session" }
            }
        }
        log.info { "Public WS client destroyed" }
    }

    @EventListener(ContextClosedEvent::class)
    fun onContextClosed() {
        log.info { "Context closed, shutting down public WS client" }
        destroy()
    }
}
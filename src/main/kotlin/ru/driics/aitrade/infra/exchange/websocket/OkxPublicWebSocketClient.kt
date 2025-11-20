package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsCandleUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTickerUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTypeRefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@Component
class OkxPublicWebSocketClient(
    private val httpClient: HttpClient,
    private val okxProperties: OkxProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = KotlinLogging.logger {}

    // Lifecycle & State
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var activeSession: DefaultClientWebSocketSession? = null

    // Subscriptions Registry (Thread-safe)
    // Set<SubscriptionArg> where SubscriptionArg is a Map representation
    private val subscriptions = ConcurrentHashMap.newKeySet<Map<String, String>>()

    // Flows
    private val _tickerFlow = MutableSharedFlow<OkxWsTickerUpdate>(
        replay = 1,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tickerFlow: SharedFlow<OkxWsTickerUpdate> = _tickerFlow.asSharedFlow()

    private val _candleFlow = MutableSharedFlow<Pair<String, OkxWsCandleUpdate>>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val candleFlow: SharedFlow<Pair<String, OkxWsCandleUpdate>> = _candleFlow.asSharedFlow()

    private companion object {
        const val PING_INTERVAL = 20_000L
        const val MAX_RECONNECT_DELAY = 30_000L
        const val CHANNEL_TICKERS = "tickers"
        const val CHANNEL_CANDLES_PREFIX = "candle"
    }

    @PostConstruct
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info { "Starting OKX Public WebSocket Client..." }
            scope.launch { connectLoop() }
        }
    }

    private suspend fun connectLoop() {
        var attempt = 0

        while (scope.isActive && isRunning.get()) {
            try {
                val url = okxProperties.publicWsUrl()
                httpClient.webSocket(urlString = url, request = {
                    headers.append("User-Agent", "AiTrader/1.0 (+okx ws)")
                    headers.append("Accept", "application/json")
                }) {
                    log.info { "Connected to OKX Public WS: $url" }
                    activeSession = this
                    attempt = 0 // Reset backoff on success

                    // 1. Restore Subscriptions
                    resubscribeAll(this)

                    // 2. Heartbeat
                    launchHeartbeat()

                    // 3. Process Messages
                    processIncomingMessages()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Public WS disconnected/error: ${e.message}" }
            } finally {
                activeSession = null
            }

            if (isRunning.get()) {
                val delayMs = calculateBackoff(attempt)
                log.info { "Reconnecting in ${delayMs}ms (attempt $attempt)" }
                delay(delayMs)
                attempt++
            }
        }
    }

    private fun DefaultClientWebSocketSession.launchHeartbeat() = launch {
        while (isActive) {
            try {
                send(Frame.Text("ping"))
                delay(PING_INTERVAL)
            } catch (_: Exception) {
                break // Exit loop to trigger reconnect in main loop
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.processIncomingMessages() {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> handleTextFrame(frame)
                is Frame.Ping -> send(Frame.Pong(frame.data))
                is Frame.Close -> log.info { "Public WS server closed connection: ${frame.readReason()}" }
                else -> Unit
            }
        }
    }

    private fun handleTextFrame(frame: Frame.Text) {
        val text = frame.readText()
        if (text == "pong") return

        try {
            // Optimization: Parse to JsonNode once, then map to DTO
            val node = objectMapper.readTree(text)

            if (node.has("event")) {
                // Handle acks/errors
                return
            }

            val arg = node.path("arg")
            val channel = arg.path("channel").asText()
            val instId = arg.path("instId").asText()

            when {
                channel == CHANNEL_TICKERS -> {
                    val dto = objectMapper.treeToValue(node, OkxWsTypeRefs.ticker)
                    dto.data?.forEach { _tickerFlow.tryEmit(it) }
                }
                channel.startsWith(CHANNEL_CANDLES_PREFIX) -> {
                    val dto = objectMapper.treeToValue(node, OkxWsTypeRefs.candle)
                    dto.data?.forEach { candle ->
                        if (instId.isNotEmpty()) {
                            _candleFlow.tryEmit(instId to candle)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.trace(e) { "Failed to parse message: $text" }
        }
    }

    // =========================================================================
    // Subscription Management
    // =========================================================================

    suspend fun subscribeTicker(instId: String) {
        val args = mapOf("channel" to CHANNEL_TICKERS, "instId" to instId)
        addSubscription(args)
    }

    suspend fun subscribeCandles(instId: String, period: String) {
        val args = mapOf("channel" to "$CHANNEL_CANDLES_PREFIX$period", "instId" to instId)
        addSubscription(args)
    }

    private suspend fun addSubscription(args: Map<String, String>) {
        if (subscriptions.add(args)) {
            // If new, send immediately if connected
            activeSession?.let { session ->
                if (session.isActive) {
                    sendSubscribeOp(session, listOf(args))
                }
            }
        }
    }

    private suspend fun resubscribeAll(session: DefaultClientWebSocketSession) {
        if (subscriptions.isNotEmpty()) {
            log.info { "Resubscribing to ${subscriptions.size} channels..." }
            // OKX supports batching args
            sendSubscribeOp(session, subscriptions.toList())
        }
    }

    private suspend fun sendSubscribeOp(session: DefaultClientWebSocketSession, args: List<Map<String, String>>) {
        try {
            val payload = mapOf(
                "op" to "subscribe",
                "args" to args
            )
            session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
        } catch (e: Exception) {
            log.warn(e) { "Failed to send subscription frame" }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val base = 1000.0 * 2.0.pow(min(attempt, 6)) // Max base ~64 sec
        return (min(base.toLong(), MAX_RECONNECT_DELAY) + Random.nextLong(0, 1000))
    }

    @PreDestroy
    fun destroy() {
        log.info { "Shutting down OKX Public WS Client" }
        isRunning.set(false)
        scope.cancel()

        runBlocking {
            try {
                withTimeoutOrNull(2000) {
                    activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "App Shutdown"))
                }
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}
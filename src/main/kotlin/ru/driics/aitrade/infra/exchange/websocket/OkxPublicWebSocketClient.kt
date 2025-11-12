package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
class OkxPublicWebSocketClient(
    private val httpClient: HttpClient,
    private val okxProperties: OkxProperties
) {
    private val log = KotlinLogging.logger {}
    private val mapper = jacksonObjectMapper()

    private val session = AtomicReference<DefaultClientWebSocketSession?>(null)
    private val connected = AtomicBoolean(false)

    private val _tickerFlow = MutableSharedFlow<Map<String, Any?>>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tickerFlow: SharedFlow<Map<String, Any?>> = _tickerFlow.asSharedFlow()

    private val _candleFlow = MutableSharedFlow<Map<String, Any?>>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val candleFlow: SharedFlow<Map<String, Any?>> = _candleFlow.asSharedFlow()

    private val tickerSubs = ConcurrentHashMap.newKeySet<String>()
    private val candleSubs = ConcurrentHashMap.newKeySet<Pair<String, String>>() // instId, period

    suspend fun connect() {
        while (true) {
            try {
                val url = okxProperties.publicWsUrl()
                httpClient.webSocket(urlString = url, request = {
                    // Optional but can help with some edges/CDN
                    headers.append("User-Agent", "AiTrader/1.0 (+okx ws)")
                    headers.append("Accept", "application/json")
                    headers.append("Origin", "https://www.okx.com")
                }) {
                    connected.set(true)
                    log.info { "Connected to OKX public WS: $url" }
                    try {
                        resubscribeAll(this) // pass session to send
                        val pingJob = launchPing()
                        readLoop()
                        pingJob.cancel()
                    } finally {
                        connected.set(false)
                    }
                }
            } catch (e: Exception) {
                log.warn(e) { "Public WS disconnected, reconnecting in 5s..." }
                session.set(null)
                connected.set(false)
                delay(5_000)
            }
            yield()
        }
    }

    suspend fun subscribeTicker(instId: String) {
        tickerSubs += instId
        if (connected.get()) {
            send(
                mapOf(
                    "op" to "subscribe",
                    "args" to listOf(mapOf("channel" to "tickers", "instId" to instId))
                )
            )
        }
    }

    suspend fun subscribeCandles(instId: String, period: String) {
        candleSubs += (instId to period)
        if (connected.get()) {
            send(
                mapOf(
                    "op" to "subscribe",
                    "args" to listOf(mapOf("channel" to "candle$period", "instId" to instId))
                )
            )
        }
    }

    private suspend fun DefaultClientWebSocketSession.readLoop() {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> handleText(frame.readText())
                is Frame.Close -> {
                    closeReason.await()?.let {
                        log.warn { "Public WS close: ${it.message}" }
                    }
                    return
                }
                else -> {}
            }
        }
    }

    private fun handleText(text: String) {
        try {
            val root: Map<String, Any?> = mapper.readValue(text)
            // Ignore subscribe acks and pongs
            val event = root["event"] as? String
            if (event == "subscribe" || event == "login" || text == "pong") return

            val arg = root["arg"] as? Map<*, *>
            val channel = arg?.get("channel") as? String ?: return
            val instId = arg["instId"] as? String
            val data = (root["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return

            @Suppress("UNCHECKED_CAST")
            val payload = data as Map<String, Any?>

            when {
                channel == "tickers" -> _tickerFlow.tryEmit(payload)
                channel.startsWith("candle") -> _candleFlow.tryEmit(
                    payload + ("instId" to instId) + ("channel" to channel)
                )
            }
        } catch (e: Exception) {
            log.debug(e) { "Failed to parse public WS message: ${text.take(200)}" }
        }
    }

    private fun DefaultClientWebSocketSession.launchPing() = launch {
        while (isActive) {
            try {
                send(Frame.Text("ping"))
            } catch (e: Exception) {
                log.debug(e) { "Public WS ping failed" }
                break
            }
            delay(15_000)
        }
    }

    private suspend fun resubscribeAll(session: DefaultClientWebSocketSession) {
        for (instId in tickerSubs) {
            session.send(Frame.Text(mapper.writeValueAsString(
                mapOf("op" to "subscribe", "args" to listOf(mapOf("channel" to "tickers", "instId" to instId)))
            )))
        }
        for ((instId, period) in candleSubs) {
            session.send(Frame.Text(mapper.writeValueAsString(
                mapOf("op" to "subscribe", "args" to listOf(mapOf("channel" to "candle$period", "instId" to instId)))
            )))
        }
    }

    private suspend fun send(payload: Map<String, Any?>) {
        val currentSession = session.get() ?: return
        val json = mapper.writeValueAsString(payload)
        currentSession.send(Frame.Text(json))
    }
}
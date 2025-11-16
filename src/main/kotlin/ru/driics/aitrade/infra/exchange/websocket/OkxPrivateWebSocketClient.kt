package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsAccountUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsOrderUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsPositionUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTypeRefs
import ru.driics.aitrade.service.OkxAuthService
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

/**
 * Type-safe OKX private WebSocket client.
 *
 * - Uses REST signer parity (createAuthHeaders) and awaits "event":"login" ack
 * - One reader; ping after login only
 * - Explicit wss URL (paper/live) via OkxProperties.privateWsUrl()
 * - Typed DTO streams
 */
@Component
class OkxPrivateWebSocketClient(
    private val httpClient: HttpClient,
    private val okxProperties: OkxProperties,
    private val okxAuthService: OkxAuthService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = KotlinLogging.logger {}
    private val connected = AtomicBoolean(false)
    private val activeSession = AtomicReference<DefaultClientWebSocketSession?>(null)
    private val stopping = AtomicBoolean(false)

    private val _orderFlow = MutableSharedFlow<OkxWsOrderUpdate>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val orderFlow: SharedFlow<OkxWsOrderUpdate> = _orderFlow.asSharedFlow()

    private val _positionFlow = MutableSharedFlow<OkxWsPositionUpdate>(
        replay = 1, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val positionFlow: SharedFlow<OkxWsPositionUpdate> = _positionFlow.asSharedFlow()

    private val _accountFlow = MutableSharedFlow<OkxWsAccountUpdate>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val accountFlow: SharedFlow<OkxWsAccountUpdate> = _accountFlow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun connect() {
        var attempt = 0
        while (scope.isActive && !stopping.get()) {
            try {
                val url = okxProperties.privateWsUrl()
                httpClient.webSocket(urlString = url, request = {
                    headers.append("User-Agent", "AiTrader/1.0 (+okx ws)")
                    headers.append("Accept", "application/json")
                    headers.append("Origin", "https://www.okx.com")
                }) {
                    connected.set(true)
                    activeSession.set(this)
                    attempt = 0
                    if (!loginAndAwaitAck(this)) {
                        log.error { "OKX private WS login failed" }
                        return@webSocket
                    }

                    // Subscribe AFTER login ack
                    subscribe(this, "orders", mapOf("instType" to "SWAP"))
                    subscribe(this, "positions", mapOf("instType" to "SWAP"))
                    subscribe(this, "account", emptyMap())

                    // Heartbeat
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
                                        log.warn { "Private WS close: $it" }
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
                        log.warn { "Private WS disconnected" }
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (!scope.isActive || stopping.get()) {
                    log.info { "Private WS stopping, not reconnecting" }
                    break
                }
                // Only log reconnect message if not stopping
                if (!stopping.get()) {
                    val base = min(30_000, (1_000 shl attempt))
                    val sleep = base + Random.nextInt(0, 1_000)
                    log.warn(t) { "Private WS reconnect in ${sleep}ms (attempt=$attempt)" }
                    delay(sleep.toLong())
                }
                attempt = (attempt + 1).coerceAtMost(15)
            }
        }
    }

    private suspend fun loginAndAwaitAck(session: DefaultClientWebSocketSession): Boolean {
        val ts = clock.instant().epochSecond.toString()
        val sign = okxAuthService.sign(ts, "GET", "/users/self/verify", "")

        val payload = mapOf(
            "op" to "login",
            "args" to listOf(
                mapOf(
                    "apiKey" to okxProperties.apiKey,
                    "passphrase" to okxProperties.passphrase, // do not trim here
                    "timestamp" to ts,
                    "sign" to sign
                )
            )
        )
        session.send(Frame.Text(objectMapper.writeValueAsString(payload)))

        return try {
            withTimeout(12_000) {
                while (true) {
                    val frame = try {
                        session.incoming.receive()
                    } catch (e: ClosedReceiveChannelException) {
                        log.warn(e) { "WS incoming channel closed before login ack" }
                        return@withTimeout false
                    }
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            val root: Map<String, Any?> = runCatching { objectMapper.readValue<Map<String, Any>>(text) }.getOrElse { emptyMap() }
                            val event = root["event"] as? String
                            when (event) {
                                "login" -> {
                                    val code = (root["code"] as? String) ?: "unknown"
                                    val msg = (root["msg"] as? String) ?: ""
                                    if (code == "0") {
                                        log.info { "OKX private WS login success" }
                                        return@withTimeout true
                                    } else {
                                        log.error { "Login failed: code=$code msg=$msg" }
                                        return@withTimeout false
                                    }
                                }
                                "error" -> {
                                    val code = (root["code"] as? String) ?: "unknown"
                                    val msg = (root["msg"] as? String) ?: ""
                                    log.error { "Login error event: code=$code msg=$msg payload=${text.take(200)}" }
                                    return@withTimeout false
                                }
                            }
                        }
                        is Frame.Close -> {
                            session.closeReason.await()?.let {
                                log.error { "WS closed before login, reason=${it}" }
                            }
                            return@withTimeout false
                        }
                        else -> Unit
                    }
                }
            }
        } catch (t: TimeoutCancellationException) {
            log.error { "Login failed: no ack within timeout" }
            false
        } as Boolean
    }

    private suspend fun subscribe(session: DefaultClientWebSocketSession, channel: String, extra: Map<String, String>) {
        val payload = mapOf("op" to "subscribe", "args" to listOf(mapOf("channel" to channel) + extra))
        session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
    }

    private fun handleText(text: String) {
        try {
            val root: Map<String, Any?> = objectMapper.readValue(text)
            val arg = root["arg"] as? Map<*, *> ?: return
            val channel = arg["channel"] as? String ?: return

            when (channel) {
                "orders" -> {
                    val env = objectMapper.readValue(text, OkxWsTypeRefs.orders)
                    env.data?.forEach { _orderFlow.tryEmit(it) }
                }
                "positions" -> {
                    val env = objectMapper.readValue(text,OkxWsTypeRefs.positions)
                    env.data?.forEach { _positionFlow.tryEmit(it) }
                }
                "account" -> {
                    val env = objectMapper.readValue(text, OkxWsTypeRefs.account)
                    env.data?.forEach { _accountFlow.tryEmit(it) }
                }
            }
        } catch (e: Exception) {
            log.trace(e) { "Private WS parse failed: ${text.take(200)}" }
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
                    } ?: log.warn { "Private WS session close timed out" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Error closing private WS session" }
            }
        }
        log.info { "Private WS client destroyed" }
    }

    @EventListener(ContextClosedEvent::class)
    fun onContextClosed() {
        log.info { "Context closed, shutting down private WS client" }
        destroy()
    }
}
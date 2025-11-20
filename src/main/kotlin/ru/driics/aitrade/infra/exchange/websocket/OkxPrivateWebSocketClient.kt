package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.databind.JsonNode
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
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsAccountUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsOrderUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsPositionUpdate
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTypeRefs
import ru.driics.aitrade.service.OkxAuthService
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@Component
class OkxPrivateWebSocketClient(
    private val httpClient: HttpClient,
    private val okxProperties: OkxProperties,
    private val okxAuthService: OkxAuthService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = KotlinLogging.logger {}

    // Lifecycle control
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var activeSession: DefaultClientWebSocketSession? = null

    // Flows
    // Optimization: Use extraBufferCapacity to handle bursts during high volatility
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

    // Constants
    private companion object {
        const val PING_INTERVAL_MS = 20_000L
        const val LOGIN_TIMEOUT_MS = 10_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val CHANNEL_ORDERS = "orders"
        const val CHANNEL_POSITIONS = "positions"
        const val CHANNEL_ACCOUNT = "account"
        const val INST_TYPE_SWAP = "SWAP"
    }

    @PostConstruct
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info { "Starting OKX Private WebSocket Client..." }
            scope.launch { connectLoop() }
        }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (scope.isActive && isRunning.get()) {
            try {
                val url = okxProperties.privateWsUrl()
                httpClient.webSocket(urlString = url, request = {
                    headers.append("User-Agent", "AiTrader/1.0 (+okx ws)")
                    headers.append("Accept", "application/json")
                }) {
                    log.info { "Connected to OKX Private WS" }
                    activeSession = this

                    // 1. Login
                    if (!performLogin(this)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Login Failed"))
                        return@webSocket
                    }

                    // 2. Subscribe
                    sendSubscriptions(this)

                    // Reset attempts on successful login sequence
                    attempt = 0

                    // 3. Start Heartbeat & Process Messages
                    launchHeartbeat()
                    processIncomingMessages()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "WS connection error/disconnected" }
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

    private suspend fun performLogin(session: DefaultClientWebSocketSession): Boolean {
        val ts = clock.instant().epochSecond.toString()
        val sign = okxAuthService.sign(ts, "GET", "/users/self/verify", "")

        val payload = mapOf(
            "op" to "login",
            "args" to listOf(
                mapOf(
                    "apiKey" to okxProperties.apiKey,
                    "passphrase" to okxProperties.passphrase,
                    "timestamp" to ts,
                    "sign" to sign
                )
            )
        )

        session.send(Frame.Text(objectMapper.writeValueAsString(payload)))

        return try {
            withTimeout(LOGIN_TIMEOUT_MS) {
                // Consume frames until we get a login event
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        val node = runCatching { objectMapper.readTree(frame.readText()) }.getOrNull()
                        if (node != null && node.path("event").asText() == "login") {
                            val code = node.path("code").asText()
                            return@withTimeout if (code == "0") {
                                log.info { "OKX Login Successful" }
                                true
                            } else {
                                log.error { "OKX Login Failed: ${node.path("msg").asText()}" }
                                false
                            }
                        }
                    }
                }
                false // Channel closed before login
            }
        } catch (e: TimeoutCancellationException) {
            log.error(e) { "OKX Login timed out" }
            false
        }
    }

    private suspend fun sendSubscriptions(session: DefaultClientWebSocketSession) {
        val subs = listOf(
            mapOf("channel" to CHANNEL_ORDERS, "instType" to INST_TYPE_SWAP),
            mapOf("channel" to CHANNEL_POSITIONS, "instType" to INST_TYPE_SWAP),
            mapOf("channel" to CHANNEL_ACCOUNT)
        )
        val payload = mapOf("op" to "subscribe", "args" to subs)
        session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
        log.info { "Subscriptions sent" }
    }

    private fun DefaultClientWebSocketSession.launchHeartbeat() = launch {
        while (isActive) {
            delay(PING_INTERVAL_MS)
            try {
                send(Frame.Text("ping"))
            } catch (e: Exception) {
                log.debug(e) { "Failed to send ping" }
                break
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.processIncomingMessages() {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> handleTextFrame(frame)
                is Frame.Ping -> send(Frame.Pong(frame.data)) // Auto-reply to protocol pings
                is Frame.Close -> log.info { "WS Closed: ${frame.readReason()}" }
                else -> Unit
            }
        }
    }

    private fun handleTextFrame(frame: Frame.Text) {
        val text = frame.readText()
        if (text == "pong") return // OKX text-based pong

        try {
            // Optimization: Read tree only once
            val node = objectMapper.readTree(text)

            // Check for data update ("arg" + "data")
            if (node.has("data")) {
                dispatchData(node)
            } else if (node.has("event")) {
                handleEvent(node)
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to parse WS message: $text" }
        }
    }

    private fun dispatchData(node: JsonNode) {
        val channel = node.path("arg").path("channel").asText()

        // Using runCatching to prevent one bad message from crashing the loop
        runCatching {
            when (channel) {
                CHANNEL_ORDERS -> {
                    // Convert specific node branch or full JSON to DTO
                    val dto = objectMapper.treeToValue(node, OkxWsTypeRefs.orders)
                    dto.data?.forEach { _orderFlow.tryEmit(it) }
                }
                CHANNEL_POSITIONS -> {
                    val dto = objectMapper.treeToValue(node, OkxWsTypeRefs.positions)
                    dto.data?.forEach { _positionFlow.tryEmit(it) }
                }
                CHANNEL_ACCOUNT -> {
                    val dto = objectMapper.treeToValue(node, OkxWsTypeRefs.account)
                    dto.data?.forEach { _accountFlow.tryEmit(it) }
                }
                else -> log.trace { "Ignored channel: $channel" }
            }
        }.onFailure { e ->
            log.warn(e) { "Mapping error for channel $channel" }
        }
    }

    private fun handleEvent(node: JsonNode) {
        val event = node.path("event").asText()
        if (event == "error") {
            log.error { "WS Error Event: ${node.path("msg").asText()} (code ${node.path("code")})" }
        } else if (event == "subscribe") {
            log.debug { "Subscribed: ${node.path("arg")}" }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val baseDelay = 1000L * (2.0.pow(min(attempt, 6).toDouble())).toLong() // Max 64s base
        return min(baseDelay, MAX_RECONNECT_DELAY_MS) + Random.nextLong(0, 1000)
    }

    @PreDestroy
    fun destroy() {
        log.info { "Shutting down OKX Private WS..." }
        isRunning.set(false)

        // Close session first
        runBlocking {
            try {
                withTimeoutOrNull(2000) {
                    activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "App Shutdown"))
                }
            } catch (_: Exception) {
                // Ignore errors during shutdown
            }
        }

        // Cancel scope
        scope.cancel()
    }
}
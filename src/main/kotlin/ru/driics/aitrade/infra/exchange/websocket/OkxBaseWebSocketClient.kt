package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KLogger
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.utils.io.CancellationException
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Connection lifecycle states for WebSocket clients.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val sessionId: String = UUID.randomUUID().toString()) : ConnectionState()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

/**
 * Configuration for WebSocket connection behavior.
 */
data class WebSocketConfig(
    val pingIntervalMs: Long = 20_000L,
    val shutdownTimeoutMs: Long = 2_000L,
    val maxReconnectDelayMs: Long = 30_000L,
    val baseReconnectDelayMs: Long = 1_000L,
    val maxBackoffExponent: Int = 6,
    val jitterMs: Long = 1_000L,
    val userAgent: String = "AiTrader/1.0"
) {
    init {
        require(pingIntervalMs > 0) { "pingIntervalMs must be positive" }
        require(maxReconnectDelayMs >= baseReconnectDelayMs) { "maxReconnectDelay must be >= baseReconnectDelay" }
    }
}

/**
 * Abstract base class for OKX WebSocket clients.
 * Handles connection lifecycle, heartbeat, and reconnection with exponential backoff.
 */
abstract class OkxBaseWebSocketClient(
    protected val httpClient: HttpClient,
    protected val objectMapper: ObjectMapper,
    protected val config: WebSocketConfig = WebSocketConfig()
) {
    protected abstract val log: KLogger
    protected abstract val wsUrl: String

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName(this::class.simpleName ?: "OkxWsClient")
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val sessionMutex = Mutex()
    private var _activeSession: DefaultClientWebSocketSession? = null

    @Volatile
    private var isRunning = false

    val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    protected open fun start() {
        synchronized(this) {
            if (isRunning) {
                log.debug { "Client is already running" }
                return
            }
            isRunning = true
        }

        log.info { "Starting ${this::class.simpleName}..." }
        scope.launch { connectionLoop() }
    }

    protected open fun stop() {
        log.info { "Stopping ${this::class.simpleName}..." }

        synchronized(this) {
            if (!isRunning) return
            isRunning = false
        }

        runBlocking {
            closeSession("App Shutdown")
        }

        scope.cancel()
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun closeSession(reason: String) {
        sessionMutex.withLock {
            _activeSession?.let { session ->
                withTimeoutOrNull(config.shutdownTimeoutMs) {
                    runCatching {
                        session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                    }
                }
                _activeSession = null
            }
        }
    }

    private suspend fun connectionLoop() {
        var attempt = 0

        while (scope.isActive && isRunning) {
            updateState(if (attempt == 0) ConnectionState.Connecting else ConnectionState.Reconnecting(attempt, 0))

            val success = runConnectionAttempt()

            if (success) {
                attempt = 0
            }

            if (isRunning && scope.isActive) {
                attempt++
                val delayMs = calculateBackoff(attempt)
                updateState(ConnectionState.Reconnecting(attempt, delayMs))
                log.info { "Reconnecting in ${delayMs}ms (attempt $attempt)" }
                delay(delayMs)
            }
        }
    }

    private suspend fun runConnectionAttempt(): Boolean {
        return try {
            httpClient.webSocket(urlString = wsUrl) {
                configureHeaders()
                handleSession(this)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Connection failed" }
            false
        } finally {
            sessionMutex.withLock {
                _activeSession = null
            }
            updateState(ConnectionState.Disconnected)
        }
    }

    private fun WebSocketSession.configureHeaders() {
        // Headers are set in the request block, not here
    }

    private suspend fun handleSession(session: DefaultClientWebSocketSession) {
        sessionMutex.withLock {
            _activeSession = session
        }
        log.info { "Connected to $wsUrl" }

        val initSuccess = onSessionEstablished(session)
        if (!initSuccess) {
            updateState(ConnectionState.Failed("Initialization failed"))
            return
        }

        updateState(ConnectionState.Connected())

        // Run the frame loop INLINE so the enclosing httpClient.webSocket { } block stays suspended
        // for the entire life of the connection. The previous code detached this into `scope.launch`
        // and returned immediately, which made ktor close the session right after connect — a
        // connect -> login -> subscribe -> teardown -> reconnect storm. The heartbeat runs as a child
        // of this coroutineScope and is cancelled when the frame loop ends (connection drop).
        // Invariant preserved: `session.incoming` has a single reader at a time (login reads it during
        // onSessionEstablished, then processIncomingFrames takes over here).
        coroutineScope {
            val heartbeatJob = launch { runHeartbeat(session) }
            try {
                processIncomingFrames(session)
            } finally {
                heartbeatJob.cancel()
                onSessionClosed()
            }
        }
    }

    private suspend fun runHeartbeat(session: DefaultClientWebSocketSession) {
        while (session.isActive) {
            delay(config.pingIntervalMs)
            val sent = sendPing(session)
            if (!sent) break
        }
    }

    private suspend fun sendPing(session: DefaultClientWebSocketSession): Boolean {
        return try {
            session.send(Frame.Text("ping"))
            true
        } catch (e: Exception) {
            log.debug(e) { "Ping failed" }
            false
        }
    }

    private suspend fun processIncomingFrames(session: DefaultClientWebSocketSession) {
        for (frame in session.incoming) {
            processFrame(session, frame)
        }
    }

    private suspend fun processFrame(session: DefaultClientWebSocketSession, frame: Frame) {
        when (frame) {
            is Frame.Text -> handleTextFrame(frame.readText())
            is Frame.Ping -> session.send(Frame.Pong(frame.data))
            is Frame.Close -> log.info { "Server closed: ${frame.readReason()}" }
            else -> Unit
        }
    }

    internal fun handleTextFrame(text: String) {
        if (text == "pong") return

        runCatching {
            val node = objectMapper.readTree(text)
            dispatchMessage(node)
        }.onFailure { e ->
            log.error(e) { "Failed to parse message: ${text.take(200)}" }
        }
    }

    internal fun calculateBackoff(attempt: Int): Long {
        val exponent = min(attempt, config.maxBackoffExponent)
        val baseDelay = config.baseReconnectDelayMs * 2.0.pow(exponent.toDouble()).toLong()
        val clampedDelay = min(baseDelay, config.maxReconnectDelayMs)
        val jitter = Random.nextLong(0, config.jitterMs)
        return clampedDelay + jitter
    }

    protected suspend fun sendMessage(payload: Any): Boolean {
        val session = sessionMutex.withLock { _activeSession } ?: run {
            log.warn { "Cannot send: not connected" }
            return false
        }

        return try {
            val json = objectMapper.writeValueAsString(payload)
            session.send(Frame.Text(json))
            true
        } catch (e: Exception) {
            log.warn(e) { "Send failed" }
            false
        }
    }

    private fun updateState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * Called when a session is established. Return false to abort connection.
     */
    protected abstract suspend fun onSessionEstablished(session: DefaultClientWebSocketSession): Boolean

    /**
     * Called when session is closed.
     */
    protected open fun onSessionClosed() {}

    /**
     * Dispatch parsed JSON message to appropriate handlers.
     */
    protected abstract fun dispatchMessage(node: JsonNode)
}
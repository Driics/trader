package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.infra.exchange.websocket.dto.*
import ru.driics.aitrade.service.OkxAuthService
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

@Component
class OkxPrivateWebSocketClient(
    httpClient: HttpClient,
    objectMapper: ObjectMapper,
    private val okxProperties: OkxProperties,
    private val okxAuthService: OkxAuthService,
    private val clock: Clock,
) : OkxBaseWebSocketClient(
    httpClient = httpClient,
    objectMapper = objectMapper,
    config = WebSocketConfig(pingIntervalMs = 20_000L)
) {
    override val log: KLogger = KotlinLogging.logger {}
    override val wsUrl: String get() = okxProperties.privateWsUrl()

    // Flows with appropriate configurations
    private val _orderFlowMutable = FlowFactory.highThroughput<OkxWsOrderUpdate>(512).first
    val orderFlow: SharedFlow<OkxWsOrderUpdate> = _orderFlowMutable.asSharedFlow()

    private val _positionFlowMutable = FlowFactory.stateful<OkxWsPositionUpdate>(128).first
    val positionFlow: SharedFlow<OkxWsPositionUpdate> = _positionFlowMutable.asSharedFlow()

    private val _accountFlowMutable = FlowFactory.stateful<OkxWsAccountUpdate>(64).first
    val accountFlow: SharedFlow<OkxWsAccountUpdate> = _accountFlowMutable.asSharedFlow()

    private companion object Channels {
        const val ORDERS = "orders"
        const val POSITIONS = "positions"
        const val ACCOUNT = "account"
        const val INST_TYPE_SWAP = "SWAP"
        val LOGIN_TIMEOUT = 10.seconds
    }

    @PostConstruct
    override fun start() = super.start()

    @PreDestroy
    override fun stop() = super.stop()

    // =========================================================================
    // Session Lifecycle
    // =========================================================================

    override suspend fun onSessionEstablished(session: DefaultClientWebSocketSession): Boolean {
        if (!performLogin(session)) {
            return false
        }
        sendSubscriptions(session)
        return true
    }

    private suspend fun performLogin(session: DefaultClientWebSocketSession): Boolean {
        val loginPayload = buildLoginPayload()
        session.send(Frame.Text(objectMapper.writeValueAsString(loginPayload)))

        return try {
            withTimeout(LOGIN_TIMEOUT) {
                awaitLoginResponse(session)
            }
        } catch (e: Exception) {
            log.error(e) { "Login failed" }
            false
        }
    }

    private fun buildLoginPayload(): Map<String, Any> {
        val timestamp = clock.instant().epochSecond.toString()
        val signature = okxAuthService.sign(timestamp, "GET", "/users/self/verify", "")

        return mapOf(
            "op" to "login",
            "args" to listOf(
                mapOf(
                    "apiKey" to okxProperties.apiKey,
                    "passphrase" to okxProperties.passphrase,
                    "timestamp" to timestamp,
                    "sign" to signature
                )
            )
        )
    }

    private suspend fun awaitLoginResponse(session: DefaultClientWebSocketSession): Boolean {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue

            val node = runCatching { objectMapper.readTree(frame.readText()) }.getOrNull()
                ?: continue

            if (node.path("event").asText() != "login") continue

            val code = node.path("code").asText()
            return if (code == "0") {
                log.info { "Login successful" }
                true
            } else {
                log.error { "Login rejected: ${node.path("msg").asText()}" }
                false
            }
        }
        return false
    }

    private suspend fun sendSubscriptions(session: DefaultClientWebSocketSession) {
        val subscriptions = listOf(
            mapOf("channel" to ORDERS, "instType" to INST_TYPE_SWAP),
            mapOf("channel" to POSITIONS, "instType" to INST_TYPE_SWAP),
            mapOf("channel" to ACCOUNT)
        )

        val payload = mapOf("op" to "subscribe", "args" to subscriptions)
        session.send(Frame.Text(objectMapper.writeValueAsString(payload)))
        log.info { "Subscribed to ${subscriptions.size} private channels" }
    }

    // =========================================================================
    // Message Dispatching
    // =========================================================================

    override fun dispatchMessage(node: JsonNode) {
        when {
            node.has("data") -> dispatchDataUpdate(node)
            node.has("event") -> handleEventMessage(node)
        }
    }

    private fun dispatchDataUpdate(node: JsonNode) {
        val channel = node.path("arg").path("channel").asText()

        runCatching {
            when (channel) {
                ORDERS -> {
                    objectMapper.treeToValue(node, OkxWsTypeRefs.orders)
                        .data
                        ?.forEach { _orderFlowMutable.tryEmit(it) }
                }
                POSITIONS -> {
                    objectMapper.treeToValue(node, OkxWsTypeRefs.positions)
                        .data
                        ?.forEach { _positionFlowMutable.tryEmit(it) }
                }
                ACCOUNT -> {
                    objectMapper.treeToValue(node, OkxWsTypeRefs.account)
                        .data
                        ?.forEach { _accountFlowMutable.tryEmit(it) }
                }
                else -> log.trace { "Unhandled channel: $channel" }
            }
        }.onFailure { e ->
            log.warn(e) { "Failed to parse $channel update" }
        }
    }

    private fun handleEventMessage(node: JsonNode) {
        when (val event = node.path("event").asText()) {
            "error" -> log.error { "WS error: ${node.path("msg").asText()} (${node.path("code")})" }
            "subscribe" -> log.debug { "Subscribed: ${node.path("arg")}" }
            else -> log.trace { "Event: $event" }
        }
    }
}
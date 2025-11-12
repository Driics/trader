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
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class OkxPrivateWebSocketClient(
    private val httpClient: HttpClient,
    private val okxProperties: OkxProperties,
    private val clock: Clock
) {
    private val log = KotlinLogging.logger {}
    private val mapper = jacksonObjectMapper()

    private val _orderFlow = MutableSharedFlow<Map<String, Any?>>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val orderFlow: SharedFlow<Map<String, Any?>> = _orderFlow.asSharedFlow()

    private val _positionFlow = MutableSharedFlow<Map<String, Any?>>(
        replay = 1, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val positionFlow: SharedFlow<Map<String, Any?>> = _positionFlow.asSharedFlow()

    private val _accountFlow = MutableSharedFlow<Map<String, Any?>>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val accountFlow: SharedFlow<Map<String, Any?>> = _accountFlow.asSharedFlow()

    private var reconnectDelay = 1_000L
    suspend fun connect() {
        while (true) {
            try {
                httpClient.webSocket(
                    host = "ws.okx.com",
                    port = 8443,
                    path = "/ws/v5/private"
                ) {
                    if (!login()) {
                        log.error { "OKX private WS login failed" }
                        return@webSocket
                    }
                    subscribe("orders", mapOf("instType" to "SWAP"))
                    subscribe("positions", mapOf("instType" to "SWAP"))
                    subscribe("account", emptyMap())

                    val pingJob = launch { // heartbeat
                        while (isActive) {
                            try { send(Frame.Text("ping")) } catch (_: Exception) { break }
                            delay(15_000)
                        }
                    }
                    try {
                        readLoop()
                    } finally {
                        pingJob.cancel()
                    }
                }
            } catch (e: Exception) {
                log.warn(e) { "Private WS disconnected, reconnecting in ${reconnectDelay}ms..." }
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(30_000L)
            }
            yield()
        }
    }

    private suspend fun DefaultClientWebSocketSession.login(): Boolean {
        val ts = clock.instant().epochSecond.toString()
        val method = "GET"
        val path = "/users/self/verify"
        val sign = sign(ts + method + path, okxProperties.secret)
        val msg = mapOf(
            "op" to "login",
            "args" to listOf(
                mapOf(
                    "apiKey" to okxProperties.key,
                    "passphrase" to okxProperties.passphrase,
                    "timestamp" to ts,
                    "sign" to sign
                )
            )
        )
        send(Frame.Text(mapper.writeValueAsString(msg)))

        for (frame in incoming) {
            if (frame is Frame.Text) {
                val response: Map<String, Any?> = mapper.readValue(frame.readText())
                if (response["event"] == "login") {
                    val code = response["code"] as? String
                    if (code == "0") return true
                    log.error { "Login failed: ${response["msg"]}" }
                    return false
                }
            }
        }

        return false
    }

    private suspend fun DefaultClientWebSocketSession.subscribe(channel: String, extra: Map<String, String>) {
        val payload = mapOf(
            "op" to "subscribe",
            "args" to listOf(mapOf("channel" to channel) + extra)
        )
        send(Frame.Text(mapper.writeValueAsString(payload)))
    }

    private suspend fun DefaultClientWebSocketSession.readLoop() {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> handleText(frame.readText())
                is Frame.Close -> {
                    closeReason.await()?.let {
                        log.warn { "Private WS close: ${it.message}" }
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

            val event = root["event"] as? String
            if (event == "subscribe" || event == "login" || text == "pong") return

            val arg = root["arg"] as? Map<*, *>
            val channel = arg?.get("channel") as? String ?: return
            val dataList = root["data"] as? List<*> ?: return
            if (dataList.isEmpty()) return
            val payload = dataList.firstOrNull() as? Map<*, *> ?: return
            @Suppress("UNCHECKED_CAST")
            val typedPayload = payload as Map<String, Any?>

            when (channel) {
                "orders" -> _orderFlow.tryEmit(typedPayload)
                "positions" -> _positionFlow.tryEmit(typedPayload)
                "account" -> _accountFlow.tryEmit(typedPayload)
            }
        } catch (e: Exception) {
            log.debug(e) { "Failed to parse private WS message: ${text.take(200)}" }
        }
    }

    private fun sign(message: String, secretKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(raw)
    }
}
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
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.service.OkxAuthService
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class OkxPrivateWebSocketClient(
    private val httpClient: HttpClient,
    private val okxProperties: OkxProperties,
    private val okxAuthService: OkxAuthService,
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
                    urlString = okxProperties.privateWsUrl(),
                    request = {
                        headers.append("User-Agent", "AiTrader/1.0 (+okx ws)")
                        headers.append("Accept", "application/json")
                        headers.append("Origin", "https://www.okx.com")
                    }
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
        val ts = (System.currentTimeMillis() / 1000.0).toString()
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
        send(Frame.Text(mapper.writeValueAsString(payload)))

        return try {
            withTimeout(5_000) {
                while (true) {
                    when (val frame = this@login.incoming.receive()) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            val root: Map<String, Any?> = mapper.readValue(text)
                            val event = root["event"] as? String
                            if (event == "login") {
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
                            // ignore other frames before login ack
                        }

                        else -> { /* ignore */ }
                    }
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Login failed: no ack within timeout" }
            false
        } as Boolean
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
                is Frame.Ping -> {
                    try {
                        send(Frame.Pong(frame.data))
                    } catch (e: Exception) {
                        log.warn(e) { "Pong wasn't send" }
                        return
                    }
                }
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
            val payloads = dataList.mapNotNull { it as? Map<String, Any?> }
            if (payloads.isEmpty()) return
            payloads.forEach { payload ->
                when (channel) {
                    "orders" -> _orderFlow.tryEmit(payload)
                    "positions" -> _positionFlow.tryEmit(payload)
                    "account" -> _accountFlow.tryEmit(payload)
                }
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
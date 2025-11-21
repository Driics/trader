package ru.driics.aitrade.config

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders.ContentEncoding
import io.ktor.serialization.kotlinx.json.*
import io.netty.handler.codec.compression.StandardCompressionOptions.deflate
import io.netty.handler.codec.compression.StandardCompressionOptions.gzip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import kotlin.time.Duration.Companion.seconds

@Configuration
class OkxKtorClientConfig {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Bean
    fun okxKtorClient(
        okxHttpProps: OkxHttpProperties,
        tradingProperties: TradingProperties,
        env: Environment
    ): HttpClient = HttpClient(CIO) {
        // 1. Engine Configuration (CIO)
        engine {
            maxConnectionsCount = okxHttpProps.pool.maxConnections
            endpoint {
                maxConnectionsPerRoute = 20
                pipelineMaxSize = 10
                keepAliveTime = 30_000
                connectTimeout = 10_000
                connectAttempts = 3
            }
            // Dedicated dispatcher for IO operations to prevent blocking main threads
            dispatcher = Dispatchers.IO.limitedParallelism(8)
        }

        // 2. WebSockets
        install(WebSockets) {
            pingInterval = 20.seconds
            maxFrameSize = 10 * 1024 * 1024 // 10 MB
        }

        // 3. Timeouts
        // Calculate the maximum expected SLA from business properties
        val maxBusinessSlaMs = with(tradingProperties.okxTimeouts) {
            maxOf(placeOrder, setLeverage, candles).toMillis()
        }

        install(HttpTimeout) {
            requestTimeoutMillis = maxBusinessSlaMs + 2_000L // Buffer for network latency
            connectTimeoutMillis = okxHttpProps.connectTimeoutMs.toLong()
            socketTimeoutMillis = maxBusinessSlaMs + 1_000L
        }

        // 4. Compression (Fixed: Using Ktor native DSL, not Netty)
        install(ContentEncoding) {
            gzip()
            deflate()
        }

        // 5. Serialization
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                prettyPrint = false
            })
        }

        // Logging (only in dev)
        if (env.activeProfiles.contains("dev")) {
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
                sanitizeHeader { header -> header == "OK-ACCESS-SIGN" || header == "OK-ACCESS-KEY" }
            }
        }

        // 7. Default Headers
        defaultRequest {
            header("User-Agent", "AiTrader/1.0")
            header("Accept", "application/json")
        }

        // 8. Retries
        install(HttpRequestRetry) {
            // Retry 5xx errors up to 2 times
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
        }

        // 9. Response Validation
        expectSuccess = false // We handle status codes manually below or in the client
        HttpResponseValidator {
            validateResponse { response ->
                val status = response.status.value
                when {
                    status == 429 -> throw RateLimitException("OKX Rate limit exceeded")
                    status >= 500 -> throw ServerResponseException(response, "OKX Server error: $status")
                }
            }
        }
    }
}

class RateLimitException(message: String) : Exception(message)
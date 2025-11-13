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
    @Bean
    @OptIn(ExperimentalCoroutinesApi::class)
    fun okxKtorClient(okxHttpProps: OkxHttpProperties, env: Environment): HttpClient {
        return HttpClient(CIO) {
            // Connection pooling
            engine {
                maxConnectionsCount = okxHttpProps.pool.maxConnections
                endpoint {
                    maxConnectionsPerRoute = 20
                    pipelineMaxSize = 10
                    keepAliveTime = 30_000 // 30 seconds
                    connectTimeout = 10_000
                    connectAttempts = 3
                }

                // Threading
                dispatcher = Dispatchers.IO.limitedParallelism(8)
            }

            install(WebSockets) {
                pingInterval = 20.seconds
                maxFrameSize = 10 * 1024 * 1024
            }

            // HTTP configuration
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
            }

            // Compression
            install(ContentEncoding) {
                gzip()
                deflate()
            }

            // JSON
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                })
            }

            // Logging (only in dev)
            if (env.activeProfiles.contains("dev")) {
                install(Logging) {
                    logger = Logger.DEFAULT
                    level = LogLevel.INFO
                }
            }

            // Default request config
            defaultRequest {
                header("User-Agent", "TradingSystem/1.0")
                header("Accept", "application/json")
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 2)
                exponentialDelay()
            }

            // Response validation
            HttpResponseValidator {
                validateResponse { response ->
                    when (response.status.value) {
                        in 500..599 -> throw ServerResponseException(response, "Server error")
                        429 -> throw RateLimitException("Rate limit exceeded")
                    }
                }
            }
        }
    }
}

class RateLimitException(message: String) : Exception(message)
package ru.driics.aitrade.config

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OkxKtorClientConfig(
    private val okxHttpProps: OkxHttpProperties
) {

    @Bean("okxHttpClient")
    fun okxHttpClient(): HttpClient {
        return HttpClient(CIO) {
            engine {
                maxConnectionsCount = okxHttpProps.pool.maxConnections
                endpoint {
                    connectTimeout = okxHttpProps.connectTimeoutMs.toLong()
                    connectAttempts = 3
                    keepAliveTime = 30000 // 30 seconds
                }
                threadsCount = 4
            }

            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                })
            }

            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }

            install(HttpTimeout) {
                requestTimeoutMillis = okxHttpProps.readTimeoutMs.toLong()
                connectTimeoutMillis = okxHttpProps.connectTimeoutMs.toLong()
                socketTimeoutMillis = okxHttpProps.writeTimeoutMs.toLong()
            }

            defaultRequest {
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }
            }
        }
    }
}
package ru.driics.aitrade.config

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.jackson.jackson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OkxKtorClientConfig(
    private val httpProperties: OkxHttpProperties
) {
    @Bean("okxKtorClient")
    fun okxKtorClient(): HttpClient = HttpClient(CIO) {
        engine {
            maxConnectionsCount = httpProperties.pool.maxConnections
            endpoint {
                connectTimeout = httpProperties.connectTimeoutMs.toLong()
                connectAttempts = 1
                requestTimeout = httpProperties.readTimeoutMs.toLong()
                keepAliveTime = 30_000
                pipelineMaxSize = 100
            }
        }

        install(ContentNegotiation) {
            jackson()
        }

        expectSuccess = false
    }
}
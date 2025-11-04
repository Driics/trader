package ru.driics.aitrade.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.netty.transport.AddressResolverGroup
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class OkxWebClientConfig(
    private val okxHttpProps: OkxHttpProperties
) {

    @Bean("okxConnectionProvider")
    fun okxConnectionProvider(): ConnectionProvider {
        return ConnectionProvider.builder("okx-pool")
            .maxConnections(okxHttpProps.pool.maxConnections)
            .pendingAcquireMaxCount(okxHttpProps.pool.pendingAcquireMaxCount)
            .pendingAcquireTimeout(Duration.ofMillis(okxHttpProps.pool.pendingAcquireTimeoutMs.toLong()))
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofMinutes(5))
            .evictInBackground(Duration.ofSeconds(60))
            .build()
    }

    @Bean("okxHttpClient")
    fun okxHttpClient(connectionProvider: ConnectionProvider): HttpClient {
        return HttpClient.create(connectionProvider)
            .wiretap(false) // disable in production, enable for debugging
            .compress(true)
            .followRedirect(true)
            .resolver(AddressResolverGroup.DEFAULT)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, okxHttpProps.connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(okxHttpProps.readTimeoutMs.toLong()))
            .doOnConnected { conn ->
                conn.addHandlerLast(
                    ReadTimeoutHandler(okxHttpProps.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                )
                conn.addHandlerLast(
                    WriteTimeoutHandler(okxHttpProps.writeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                )
            }
    }

    @Bean("okxWebClient")
    fun okxWebClient(httpClient: HttpClient): WebClient {
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
}
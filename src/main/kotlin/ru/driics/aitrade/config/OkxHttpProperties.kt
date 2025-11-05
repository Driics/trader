package ru.driics.aitrade.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@ConfigurationProperties(prefix = "okx.http")
@Validated
data class OkxHttpProperties(
    var clientType: String = "ktor", // "ktor" | "resttemplate" (rollout flag)
    var connectTimeoutMs: Int = 5000,
    var readTimeoutMs: Int = 30000,
    var writeTimeoutMs: Int = 10000,
    var pool: PoolConfig = PoolConfig()
) {
    data class PoolConfig(
        var maxConnections: Int = 200,
        var pendingAcquireMaxCount: Int = 2000,
        var pendingAcquireTimeoutMs: Int = 5000
    )
}
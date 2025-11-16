package ru.driics.aitrade.infra.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Optional Redis adapter for distributed caching.
 * Only enabled when redis.enabled=true in configuration.
 */
interface RedisCacheAdapter {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String, ttl: Duration)
    suspend fun delete(key: String)
    suspend fun exists(key: String): Boolean
}

/**
 * No-op implementation when Redis is not available.
 */
@Component
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "false", matchIfMissing = true)
class NoOpRedisCacheAdapter : RedisCacheAdapter {
    override suspend fun get(key: String): String? = null
    override suspend fun set(key: String, value: String, ttl: Duration) {}
    override suspend fun delete(key: String) {}
    override suspend fun exists(key: String): Boolean = false
}

/**
 * Redis implementation using Spring Data Redis (when available).
 * To enable, add spring-boot-starter-data-redis dependency and set redis.enabled=true
 */
@Component
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true")
class SpringRedisCacheAdapter(
    private val objectMapper: ObjectMapper
) : RedisCacheAdapter {
    private val log = KotlinLogging.logger {}
    
    // Note: This would require spring-boot-starter-data-redis dependency
    // For now, this is a placeholder that can be implemented when Redis is added
    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        log.debug { "Redis GET: $key (not implemented - add spring-boot-starter-data-redis)" }
        null
    }

    override suspend fun set(key: String, value: String, ttl: Duration) = withContext(Dispatchers.IO) {
        log.debug { "Redis SET: $key (not implemented - add spring-boot-starter-data-redis)" }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        log.debug { "Redis DELETE: $key (not implemented - add spring-boot-starter-data-redis)" }
    }

    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        log.debug { "Redis EXISTS: $key (not implemented - add spring-boot-starter-data-redis)" }
        false
    }
}


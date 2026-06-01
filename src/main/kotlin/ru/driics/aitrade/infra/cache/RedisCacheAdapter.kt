package ru.driics.aitrade.infra.cache

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Optional distributed (L2) cache port.
 *
 * **D2 scope decision (2026-05-31).** Distributed Redis caching is **out of scope** for the current
 * single-instance, single-`@Scheduled` deployment — local Caffeine (L1) is sufficient and there is no
 * cross-instance state to share. The default binding is therefore [NoOpRedisCacheAdapter], an honest
 * null-object: callers depend on the port and L2 simply does nothing.
 *
 * There is deliberately **no half-built implementation**. A previous placeholder reported itself as
 * "enabled" (it was not a `NoOp`) while silently dropping every read/write — the worst kind of
 * unauditable stub. If multi-instance ever lands (backlog L1/L2), implement a real adapter behind
 * `redis.enabled=true` using `spring-boot-starter-data-redis`. Until then, setting `redis.enabled=true`
 * fails fast at startup via [RedisDisabledGuard] rather than pretending to work.
 */
interface RedisCacheAdapter {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String, ttl: Duration)
    suspend fun delete(key: String)
    suspend fun exists(key: String): Boolean
}

/**
 * Default L2 binding: a no-op null-object, present unless `redis.enabled=true`.
 * [SmartCacheStrategy] depends on this type and reads "is `NoOp`" as "L2 disabled".
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
 * D2 fail-fast guard. If an operator sets `redis.enabled=true` there is no real adapter to bind, so we
 * refuse to start with a clear message instead of silently no-op'ing (or worse, reporting L2 as
 * "enabled" while doing nothing). It implements the port so it is the bean Spring resolves for that
 * condition; construction throws, surfacing the reason in the startup failure.
 */
@Component
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true")
class RedisDisabledGuard : RedisCacheAdapter {
    init {
        error(
            "redis.enabled=true but no Redis adapter is implemented. Distributed L2 cache is out of " +
                "scope for the single-instance deployment (backlog L1/L2). Either unset redis.enabled " +
                "or implement a real RedisCacheAdapter with spring-boot-starter-data-redis."
        )
    }

    override suspend fun get(key: String): String? = error("unreachable")
    override suspend fun set(key: String, value: String, ttl: Duration) = error("unreachable")
    override suspend fun delete(key: String) = error("unreachable")
    override suspend fun exists(key: String): Boolean = error("unreachable")
}

package ru.driics.aitrade.domain.ports

import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant

data class PriceUpdate(
    val instId: String,
    val price: BigDecimal,
    val timestamp: Instant
)

data class OrderEvent(
    val instId: String,
    val orderId: String,
    val clOrdId: String?,
    val state: String,
    val side: String,
    val avgPx: BigDecimal?,
    val timestamp: Instant
)

data class PositionEvent(
    val instId: String,
    val pos: BigDecimal,
    val avgPx: BigDecimal,
    val upl: BigDecimal,
    val timestamp: Instant
)

interface StreamingMarketDataPort {
    fun getRealtimePrice(instId: String): BigDecimal?

    /**
     * Like [getRealtimePrice], but returns the cached price ONLY when it is trustworthy: the
     * underlying stream is currently connected AND the price arrived within [maxAgeMs]. Returns null
     * otherwise so a caller can fall back to a REST read and never act on a silently-stale price.
     */
    fun getFreshPrice(instId: String, maxAgeMs: Long): BigDecimal?

    fun observePriceUpdates(instId: String): Flow<PriceUpdate>
    fun observeOrderUpdates(): Flow<OrderEvent>
    fun observePositionUpdates(): Flow<PositionEvent>
}
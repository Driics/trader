package ru.driics.aitrade.infra.exchange.websocket

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Standardized flow configurations for different update types.
 */
object FlowFactory {

    /**
     * High-throughput flow for frequent updates (e.g., orders, tickers).
     * - No replay: consumers get only new events
     * - Large buffer: handles bursts during volatility
     */
    fun <T> highThroughput(bufferSize: Int = 512): Pair<MutableSharedFlow<T>, SharedFlow<T>> {
        val mutable = MutableSharedFlow<T>(
            replay = 0,
            extraBufferCapacity = bufferSize,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        return mutable to mutable.asSharedFlow()
    }

    /**
     * State-like flow for position/account snapshots.
     * - Replay 1: new subscribers get latest state
     * - Moderate buffer
     */
    fun <T> stateful(bufferSize: Int = 64): Pair<MutableSharedFlow<T>, SharedFlow<T>> {
        val mutable = MutableSharedFlow<T>(
            replay = 1,
            extraBufferCapacity = bufferSize,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        return mutable to mutable.asSharedFlow()
    }

    /**
     * Low-latency flow for critical updates.
     * - Suspends on overflow to prevent data loss
     */
    fun <T> reliable(bufferSize: Int = 128): Pair<MutableSharedFlow<T>, SharedFlow<T>> {
        val mutable = MutableSharedFlow<T>(
            replay = 0,
            extraBufferCapacity = bufferSize,
            onBufferOverflow = BufferOverflow.SUSPEND
        )
        return mutable to mutable.asSharedFlow()
    }
}
package ru.driics.aitrade.common.logging

import java.math.BigDecimal

/**
 * Business event logger for important trading system events.
 * All events are logged with structured JSON format for easy parsing and analysis.
 * CorrelationId is automatically included from MDC context.
 */
object BusinessEventLogger {
    private val log = structuredLogger("BusinessEvents")

    /**
     * Log order placement event.
     */
    fun orderPlaced(
        symbol: String,
        orderId: String?,
        clOrdId: String?,
        side: String,
        contracts: BigDecimal,
        price: BigDecimal? = null,
        tp: BigDecimal? = null,
        sl: BigDecimal? = null,
        leverage: Int? = null,
        costUsd: BigDecimal? = null,
        correlationId: String? = CorrelationId.get()
    ) {
        log.info(
            event = "order_placed",
            "symbol" to symbol,
            "orderId" to orderId,
            "clOrdId" to clOrdId,
            "side" to side,
            "contracts" to contracts.toPlainString(),
            "price" to price?.toPlainString(),
            "takeProfit" to tp?.toPlainString(),
            "stopLoss" to sl?.toPlainString(),
            "leverage" to leverage,
            "costUsd" to costUsd?.toPlainString(),
            "correlationId" to correlationId
        )
    }

    /**
     * Log order rejection event.
     */
    fun orderRejected(
        symbol: String,
        clOrdId: String?,
        reason: String,
        errorCode: String? = null,
        correlationId: String? = CorrelationId.get()
    ) {
        log.warn(
            event = "order_rejected",
            "symbol" to symbol,
            "clOrdId" to clOrdId,
            "reason" to reason,
            "errorCode" to errorCode,
            "correlationId" to correlationId
        )
    }

    /**
     * Log position opened event.
     */
    fun positionOpened(
        symbol: String,
        quantity: BigDecimal,
        entryPrice: BigDecimal,
        leverage: Int,
        side: String
    ) {
        log.info(
            event = "position_opened",
            "symbol" to symbol,
            "quantity" to quantity.toPlainString(),
            "entryPrice" to entryPrice.toPlainString(),
            "leverage" to leverage,
            "side" to side
        )
    }

    /**
     * Log position closed event.
     */
    fun positionClosed(
        symbol: String,
        quantity: BigDecimal,
        entryPrice: BigDecimal,
        exitPrice: BigDecimal,
        pnl: BigDecimal
    ) {
        log.info(
            event = "position_closed",
            "symbol" to symbol,
            "quantity" to quantity.toPlainString(),
            "entryPrice" to entryPrice.toPlainString(),
            "exitPrice" to exitPrice.toPlainString(),
            "pnl" to pnl.toPlainString()
        )
    }

    /**
     * Log market data fetch event.
     */
    fun marketDataFetched(
        symbol: String,
        dataType: String,
        durationMs: Long,
        success: Boolean
    ) {
        log.debug(
            event = "market_data_fetched",
            "symbol" to symbol,
            "dataType" to dataType,
            "durationMs" to durationMs,
            "success" to success
        )
    }

    /**
     * Log AI decision event.
     */
    fun aiDecision(
        symbol: String,
        signal: String,
        confidence: BigDecimal?,
        leverage: Int?,
        riskUsd: BigDecimal?,
        correlationId: String? = CorrelationId.get()
    ) {
        log.info(
            event = "ai_decision",
            "symbol" to symbol,
            "signal" to signal,
            "confidence" to confidence?.toPlainString(),
            "leverage" to leverage,
            "riskUsd" to riskUsd?.toPlainString(),
            "correlationId" to correlationId
        )
    }

    /**
     * Log update cycle event.
     */
    fun updateCycle(
        cycleNumber: Long,
        durationMs: Long,
        symbolsProcessed: Int,
        positionsPlaced: Int,
        success: Boolean
    ) {
        log.info(
            event = "update_cycle",
            "cycleNumber" to cycleNumber,
            "durationMs" to durationMs,
            "symbolsProcessed" to symbolsProcessed,
            "positionsPlaced" to positionsPlaced,
            "success" to success
        )
    }

    /**
     * Log cache event.
     */
    fun cacheEvent(
        eventType: String,
        key: String,
        level: String,
        hit: Boolean? = null
    ) {
        log.debug(
            event = "cache_$eventType",
            "key" to key,
            "level" to level,
            "hit" to hit
        )
    }

    /**
     * Log error event with context.
     */
    fun error(
        event: String,
        error: Throwable,
        vararg context: Pair<String, Any?>
    ) {
        log.error(
            event = event,
            throwable = error,
            *context
        )
    }
}


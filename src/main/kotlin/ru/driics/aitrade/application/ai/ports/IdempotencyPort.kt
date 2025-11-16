package ru.driics.aitrade.application.ai.ports

import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal

/**
 * Port for idempotency checking and clOrdId generation.
 */
interface IdempotencyPort {
    fun generateClOrdId(
        symbol: String,
        signal: AiTradeSignalArgs,
        entryPrice: BigDecimal,
        timestamp: Long
    ): String

    fun isDuplicate(signalKey: String): Boolean
    fun recordSignal(signalKey: String)
    fun signalKey(
        symbol: String,
        signal: AiTradeSignalArgs,
        entryPrice: BigDecimal,
        timestampWindowMinutes: Long = 2
    ): String
}


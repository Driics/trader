package ru.driics.aitrade.domain.journal

import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.types.OrderSide
import java.math.BigDecimal

/**
 * Immutable rows written to the opt-in trade journal (see
 * [ru.driics.aitrade.domain.ports.TradeJournalPort]). These are persistence DTOs — flat, nullable where
 * the exchange/decision may not supply a value — kept separate from the live trading models so the
 * journal schema can evolve without coupling to hot-path types.
 */
data class JournaledOrder(
    val timestampMs: Long,
    val mode: TradingMode,
    val symbol: String,
    val instId: String,
    val side: OrderSide,
    val leverage: Int,
    val requestedContracts: BigDecimal?,
    val placedContracts: BigDecimal?,
    val entryPx: BigDecimal?,
    val tpPx: BigDecimal?,
    val slPx: BigDecimal?,
    val riskUsd: BigDecimal?,
    val costUsd: BigDecimal?,
    val clOrdId: String,
    val ordId: String?,
    /** "PLACED" or "REJECTED". */
    val status: String,
    val reason: String?,
    val demo: Boolean,
)

data class JournaledFill(
    val timestampMs: Long,
    val ordId: String,
    val clOrdId: String?,
    val instId: String,
    val side: String,
    val avgPx: BigDecimal?,
    val state: String,
)

data class JournaledPnlSnapshot(
    val timestampMs: Long,
    val cycle: Long,
    val accountValue: BigDecimal,
    val availableCash: BigDecimal,
    val totalReturn: BigDecimal,
    val realizedPnlToday: BigDecimal?,
    val openPositionsCount: Int,
)

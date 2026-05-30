package ru.driics.aitrade.domain.backtest

import java.math.BigDecimal

enum class PositionSide { LONG, SHORT }

enum class ExitReason { STOP_LOSS, TAKE_PROFIT }

/**
 * An open simulated position. [quantity] is COIN quantity (not contracts); PnL is computed in USD as
 * price-move * quantity, keeping the sim linear and instrument-agnostic. [entryFeeUsd] is the fee
 * already paid to open, folded into the trade's net PnL when it closes.
 */
data class SimPosition(
    val symbol: String,
    val side: PositionSide,
    val entryPrice: BigDecimal,
    val quantity: BigDecimal,
    val stopLoss: BigDecimal?,
    val takeProfit: BigDecimal?,
    val leverage: Int,
    val entryTimestampMs: Long,
    val entryFeeUsd: BigDecimal,
)

/** The price + reason at which an open position leaves the book within a bar. */
data class ExitFill(
    val price: BigDecimal,
    val reason: ExitReason,
)

/** A closed round-trip trade — the unit performance metrics aggregate over. [pnlUsd] is net of fees. */
data class SimTrade(
    val symbol: String,
    val side: PositionSide,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val quantity: BigDecimal,
    val entryTimestampMs: Long,
    val exitTimestampMs: Long,
    val reason: ExitReason,
    val pnlUsd: BigDecimal,
    val feesUsd: BigDecimal,
)

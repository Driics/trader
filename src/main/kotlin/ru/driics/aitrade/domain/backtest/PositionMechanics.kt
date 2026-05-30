package ru.driics.aitrade.domain.backtest

import java.math.BigDecimal

/**
 * Resolves whether an open [position] exits within [bar]'s [low, high] range.
 *
 * PESSIMISTIC: if BOTH the stop and the target are touchable in the same bar, the STOP fills — OHLC
 * alone cannot reveal intrabar ordering, so we assume the worse outcome. This keeps the equity curve
 * honest rather than optimistic. A null stop or target simply cannot trigger. Touches are inclusive
 * (price reaching a level exactly triggers it).
 *
 * Returns null if neither level is touched this bar.
 */
internal fun resolveExit(position: SimPosition, bar: Bar): ExitFill? = when (position.side) {
    PositionSide.LONG -> when {
        position.stopLoss != null && bar.low <= position.stopLoss ->
            ExitFill(position.stopLoss, ExitReason.STOP_LOSS)
        position.takeProfit != null && bar.high >= position.takeProfit ->
            ExitFill(position.takeProfit, ExitReason.TAKE_PROFIT)
        else -> null
    }

    PositionSide.SHORT -> when {
        position.stopLoss != null && bar.high >= position.stopLoss ->
            ExitFill(position.stopLoss, ExitReason.STOP_LOSS)
        position.takeProfit != null && bar.low <= position.takeProfit ->
            ExitFill(position.takeProfit, ExitReason.TAKE_PROFIT)
        else -> null
    }
}

/**
 * Realized USD PnL for a closed position: directional price move * coin [quantity], minus [feesUsd]
 * (entry + exit fees combined). LONG profits when price rises, SHORT when it falls.
 */
internal fun realizedPnlUsd(
    side: PositionSide,
    entryPrice: BigDecimal,
    exitPrice: BigDecimal,
    quantity: BigDecimal,
    feesUsd: BigDecimal,
): BigDecimal {
    val gross = when (side) {
        PositionSide.LONG -> (exitPrice - entryPrice) * quantity
        PositionSide.SHORT -> (entryPrice - exitPrice) * quantity
    }
    return gross - feesUsd
}

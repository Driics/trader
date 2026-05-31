package ru.driics.aitrade.domain.backtest

import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.Position
import ru.driics.aitrade.domain.services.OrderSizingPolicy
import ru.driics.aitrade.domain.strategy.Strategy
import ru.driics.aitrade.domain.strategy.StrategyDecision
import java.math.BigDecimal

/** Tally of entries the engine declined to open, surfaced so a run is never silently truncated. */
data class BacktestRejections(
    val noStop: Int = 0,
    val gappedThroughBracket: Int = 0,
    val unaffordable: Int = 0,
    val alreadyOpenForSymbol: Int = 0,
)

/**
 * The full outcome of a backtest over one symbol. [equityCurve] holds one marked-equity point per bar
 * (at each bar's close); because trailing positions are force-closed at the final bar, its last point
 * reconciles exactly with `startingEquity + Σ trades.pnlUsd`. [omissions] names known cost factors the
 * sim does NOT model, so the curve is never mistaken for net-of-everything.
 */
data class BacktestResult(
    val symbol: String,
    val strategyName: String,
    val metrics: PerformanceMetrics,
    val trades: List<SimTrade>,
    val equityCurve: List<BigDecimal>,
    val rejections: BacktestRejections,
    val omissions: List<String>,
) {
    companion object {
        val KNOWN_OMISSIONS = listOf(
            "funding cost not modelled (perpetual funding payments omitted)",
            "slippage not modelled (fills at exact open / stop / target prices)",
        )
    }
}

/**
 * Deterministic event-driven backtest for a single symbol behind the [Strategy] seam. Pure: same bars +
 * same strategy → same result, no clock, no I/O, no randomness.
 *
 * Per-bar ordering (the honesty-critical part — see docs/backtest-engine-design.md):
 * ```
 * step i:
 *   1. fill the pending order (decided at i-1) at open[i]
 *   2. exit pass: the open position vs bar[i] [low, high]  (incl. one just opened this bar; STOP-first)
 *      └─ at the LAST bar, an un-exited position is force-closed at close (END_OF_DATA)
 *   3. mark equity at close[i]
 *   4. if i >= warmupBars: build state from bars[0..i]; strategy.decide(); queue an order for open[i+1]
 * ```
 * Acting at `open[i+1]` (never `close[i]`) is what makes the fill non-clairvoyant.
 */
class BacktestEngine(
    private val strategy: Strategy,
    private val config: BacktestConfig,
) {
    private val sizingPolicy = OrderSizingPolicy(
        takerFeePct = config.takerFeePct,
        marginBufferPct = config.marginBufferPct,
        minLev = config.minLev,
        maxLev = config.maxLev,
    )

    fun run(symbol: String, bars: List<Bar>): BacktestResult {
        val spec = requireNotNull(config.instruments[symbol]) { "No InstrumentSpec for symbol '$symbol'" }

        var cash = config.startingEquityUsd
        var openPosition: SimPosition? = null
        var pending: PendingEntry? = null
        val trades = mutableListOf<SimTrade>()
        val equityCurve = ArrayList<BigDecimal>(bars.size)
        var rejections = BacktestRejections()

        val lastIndex = bars.lastIndex

        for (i in bars.indices) {
            val bar = bars[i]

            // 1. Fill the order decided last bar, at THIS bar's open.
            pending?.let { order ->
                pending = null
                when {
                    openPosition != null ->
                        rejections = rejections.copy(alreadyOpenForSymbol = rejections.alreadyOpenForSymbol + 1)

                    order.decision.stopLoss == null ->
                        rejections = rejections.copy(noStop = rejections.noStop + 1)

                    !validBracket(order.side, order.decision.stopLoss, order.decision.takeProfit, bar.open) ->
                        rejections = rejections.copy(gappedThroughBracket = rejections.gappedThroughBracket + 1)

                    else -> {
                        val sizing = sizeEntry(
                            decision = order.decision,
                            fillPx = bar.open,
                            equity = cash,
                            availableUsd = cash,
                            spec = spec,
                            policy = sizingPolicy,
                            riskPerTradePct = config.riskPerTradePct,
                            defaultLeverage = config.minLev,
                        )
                        when (sizing.rejection) {
                            EntryRejection.NO_STOP ->
                                rejections = rejections.copy(noStop = rejections.noStop + 1)

                            EntryRejection.UNAFFORDABLE ->
                                rejections = rejections.copy(unaffordable = rejections.unaffordable + 1)

                            null -> {
                                val qty = sizing.coinQty!!
                                openPosition = SimPosition(
                                    symbol = symbol,
                                    side = order.side,
                                    entryPrice = bar.open,
                                    quantity = qty,
                                    stopLoss = order.decision.stopLoss,
                                    takeProfit = order.decision.takeProfit,
                                    leverage = sizing.leverage,
                                    entryTimestampMs = bar.timestampMs,
                                    entryFeeUsd = qty * bar.open * config.takerFeePct,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Exit pass for the (possibly just-opened) position; force-close at end of data.
            openPosition?.let { pos ->
                val exit = resolveExit(pos, bar)
                    ?: if (i == lastIndex) ExitFill(bar.close, ExitReason.END_OF_DATA) else null
                if (exit != null) {
                    val trade = closeTrade(pos, exit, bar.timestampMs, config.takerFeePct)
                    trades += trade
                    cash += trade.pnlUsd
                    openPosition = null
                }
            }

            // 3. Mark equity at this bar's close (open position marked gross; fees fold in at close).
            val unrealized = openPosition?.let {
                realizedPnlUsd(it.side, it.entryPrice, bar.close, it.quantity, BigDecimal.ZERO)
            } ?: BigDecimal.ZERO
            val equity = cash + unrealized
            equityCurve += equity

            // 4. Decide on a bounded trailing window ending at bar i (the single look-ahead chokepoint:
            //    only past+current bars, never future) and queue for open[i+1]. The window is capped at
            //    indicatorLookback so the run stays O(n), not O(n^2).
            if (i >= config.warmupBars) {
                val from = maxOf(0, i + 1 - config.indicatorLookback)
                val state = MarketStateBuilder.build(
                    symbol = symbol,
                    bars = bars.subList(from, i + 1),
                    stepIndex = i,
                    account = AccountInfo(
                        totalReturn = totalReturn(config.startingEquityUsd, equity),
                        availableCash = cash,
                        accountValue = equity,
                    ),
                    positions = openPosition?.let { listOf(it.toDomainPosition(bar.close)) } ?: emptyList(),
                    intradayWindow = config.intradayWindow,
                )
                pending = strategy.decide(state)
                    .asSequence()
                    .filter { it.symbol == symbol }
                    .mapNotNull { d -> signalToSide(d.signal)?.let { PendingEntry(it, d) } }
                    .firstOrNull()
            }
        }

        return BacktestResult(
            symbol = symbol,
            strategyName = strategy.name,
            metrics = PerformanceMetrics.from(config.startingEquityUsd, equityCurve, trades),
            trades = trades,
            equityCurve = equityCurve,
            rejections = rejections,
            omissions = BacktestResult.KNOWN_OMISSIONS,
        )
    }

    private data class PendingEntry(val side: PositionSide, val decision: StrategyDecision)

    private fun signalToSide(signal: AiSignal): PositionSide? = when (signal) {
        AiSignal.BUY -> PositionSide.LONG
        AiSignal.SELL -> PositionSide.SHORT
        AiSignal.HOLD -> null
    }

    private fun validBracket(
        side: PositionSide,
        stop: BigDecimal?,
        target: BigDecimal?,
        fillPx: BigDecimal,
    ): Boolean {
        if (stop == null) return false
        return when (side) {
            PositionSide.LONG -> stop < fillPx && (target == null || fillPx < target)
            PositionSide.SHORT -> stop > fillPx && (target == null || fillPx > target)
        }
    }

    // Absolute USD delta (not a ratio); surfaced on the synthesized AccountInfo. No v1 strategy reads it.
    private fun totalReturn(start: BigDecimal, equity: BigDecimal): BigDecimal =
        if (start.signum() == 0) BigDecimal.ZERO else equity - start

    private fun SimPosition.toDomainPosition(markPrice: BigDecimal): Position {
        val unrealized = realizedPnlUsd(side, entryPrice, markPrice, quantity, BigDecimal.ZERO)
        val signedQty = if (side == PositionSide.LONG) quantity else quantity.negate()
        return Position(
            symbol = symbol,
            quantity = signedQty,
            entryPrice = entryPrice,
            currentPrice = markPrice,
            unrealizedPnl = unrealized,
            leverage = leverage,
        )
    }
}

/** Closes [pos] at [exit], charging the exit fee and folding the stored entry fee into net PnL. */
internal fun closeTrade(
    pos: SimPosition,
    exit: ExitFill,
    exitTimestampMs: Long,
    takerFeePct: BigDecimal,
): SimTrade {
    val exitFee = pos.quantity * exit.price * takerFeePct
    val fees = pos.entryFeeUsd + exitFee
    val pnl = realizedPnlUsd(pos.side, pos.entryPrice, exit.price, pos.quantity, fees)
    return SimTrade(
        symbol = pos.symbol,
        side = pos.side,
        entryPrice = pos.entryPrice,
        exitPrice = exit.price,
        quantity = pos.quantity,
        entryTimestampMs = pos.entryTimestampMs,
        exitTimestampMs = exitTimestampMs,
        reason = exit.reason,
        pnlUsd = pnl,
        feesUsd = fees,
    )
}

package ru.driics.aitrade.domain.analytics

import ru.driics.aitrade.domain.ports.ClosedTradeRow
import ru.driics.aitrade.domain.ports.EntryRiskRow
import ru.driics.aitrade.domain.ports.PnlSnapshotRow
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Pure realized-performance metrics over journal rows. No DB, no Spring. R links each close to the most
 * recent PLACED entry for the same instId at/before its close (one-position-per-symbol assumption).
 *
 * Max-drawdown is defined as the single worst peak-to-trough by percentage; the reported USD amount
 * corresponds to that same trough (not independently tracked).
 */
class PerformanceAnalytics {

    private val mc = MathContext(20, RoundingMode.HALF_UP)
    private val ratioScale = 4

    /** Returns the most-recent prior entry risk for [close], or null when none is usable. */
    private fun riskFor(close: ClosedTradeRow, entries: List<EntryRiskRow>): BigDecimal? =
        entries
            .filter { it.instId == close.instId && it.recordedAtMs <= close.closeTimeMs && it.riskUsd != null && it.riskUsd.signum() > 0 }
            .maxByOrNull { it.recordedAtMs }
            ?.riskUsd

    fun summary(
        closes: List<ClosedTradeRow>,
        entries: List<EntryRiskRow>,
        snapshots: List<PnlSnapshotRow>,
    ): PerformanceSummary {
        val wins = closes.filter { it.realizedPnl.signum() > 0 }
        val losses = closes.filter { it.realizedPnl.signum() < 0 }
        val scratches = closes.size - wins.size - losses.size

        val grossProfit = wins.fold(BigDecimal.ZERO) { a, c -> a + c.realizedPnl }
        val grossLoss = losses.fold(BigDecimal.ZERO) { a, c -> a + c.realizedPnl }.abs()
        val net = closes.fold(BigDecimal.ZERO) { a, c -> a + c.realizedPnl }

        val winRate = if (closes.isEmpty()) BigDecimal.ZERO
            else BigDecimal(wins.size).divide(BigDecimal(closes.size), ratioScale, RoundingMode.HALF_UP)
        val profitFactor = if (grossLoss.signum() == 0) null else grossProfit.divide(grossLoss, mc)
        val avgWin = if (wins.isEmpty()) null else grossProfit.divide(BigDecimal(wins.size), mc)
        val avgLoss = if (losses.isEmpty()) null else grossLoss.divide(BigDecimal(losses.size), mc)
        val expectancy = if (closes.isEmpty()) null else net.divide(BigDecimal(closes.size), mc)

        val rValues = closes.mapNotNull { c -> riskFor(c, entries)?.let { c.realizedPnl.divide(it, mc) } }
        val avgR = if (rValues.isEmpty()) null
            else rValues.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(rValues.size), ratioScale, RoundingMode.HALF_UP)

        val (ddUsd, ddPct) = maxDrawdown(snapshots)

        return PerformanceSummary(
            totalTrades = closes.size, wins = wins.size, losses = losses.size, scratches = scratches,
            winRate = winRate, grossProfit = grossProfit, grossLoss = grossLoss, netRealizedPnl = net,
            profitFactor = profitFactor, avgWin = avgWin, avgLoss = avgLoss, expectancyUsd = expectancy,
            avgR = avgR, rUnavailable = closes.size - rValues.size,
            maxDrawdownUsd = ddUsd, maxDrawdownPct = ddPct,
        )
    }

    fun bySymbol(
        closes: List<ClosedTradeRow>, entries: List<EntryRiskRow>, snapshots: List<PnlSnapshotRow>,
    ): List<SymbolPerformance> =
        closes.groupBy { it.symbol }.toSortedMap().map { (sym, rows) ->
            SymbolPerformance(sym, summary(rows, entries.filter { it.instId.substringBefore("-") == sym }, emptyList()))
        }

    fun equityCurve(snapshots: List<PnlSnapshotRow>): List<EquityPoint> {
        var peak = BigDecimal.ZERO
        return snapshots.sortedBy { it.timestampMs }.map { s ->
            if (s.accountValue > peak) peak = s.accountValue
            val dd = if (peak.signum() <= 0) BigDecimal.ZERO
                else (peak - s.accountValue).divide(peak, ratioScale, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
            EquityPoint(s.timestampMs, s.accountValue, dd)
        }
    }

    /**
     * Maps each close to a [TradeWithR], computing R = realizedPnl / riskUsd for the most-recent
     * prior entry of the same instId. R is null when no usable entry risk is found.
     */
    fun closedTradesWithR(closes: List<ClosedTradeRow>, entries: List<EntryRiskRow>): List<TradeWithR> =
        closes.map { c ->
            val r = riskFor(c, entries)?.let { c.realizedPnl.divide(it, ratioScale, RoundingMode.HALF_UP) }
            TradeWithR(c.posId, c.instId, c.symbol, c.side, c.realizedPnl, c.openTimeMs, c.closeTimeMs, r)
        }

    /**
     * Single worst peak-to-trough by percentage; reports USD of that same trough (not independently tracked).
     */
    private fun maxDrawdown(snapshots: List<PnlSnapshotRow>): Pair<BigDecimal, BigDecimal> {
        var peak = BigDecimal.ZERO
        var worstUsd = BigDecimal.ZERO
        var worstPct = BigDecimal.ZERO
        for (s in snapshots.sortedBy { it.timestampMs }) {
            if (s.accountValue > peak) peak = s.accountValue
            if (peak.signum() > 0) {
                val ddUsd = peak - s.accountValue
                val ddPct = ddUsd.divide(peak, ratioScale, RoundingMode.HALF_UP)
                if (ddPct > worstPct) { worstPct = ddPct; worstUsd = ddUsd } // same event
            }
        }
        return worstUsd to worstPct
    }
}

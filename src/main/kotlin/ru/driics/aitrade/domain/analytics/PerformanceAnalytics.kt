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
 */
class PerformanceAnalytics {

    private val mc = MathContext(20, RoundingMode.HALF_UP)
    private val ratioScale = 4

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

        val rValues = closes.mapNotNull { c ->
            val risk = entries
                .filter { it.instId == c.instId && it.recordedAtMs <= c.closeTimeMs && it.riskUsd != null && it.riskUsd.signum() > 0 }
                .maxByOrNull { it.recordedAtMs }
                ?.riskUsd
            risk?.let { c.realizedPnl.divide(it, mc) }
        }
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

    private fun maxDrawdown(snapshots: List<PnlSnapshotRow>): Pair<BigDecimal, BigDecimal> {
        var peak = BigDecimal.ZERO
        var maxUsd = BigDecimal.ZERO
        var maxPct = BigDecimal.ZERO
        for (s in snapshots.sortedBy { it.timestampMs }) {
            if (s.accountValue > peak) peak = s.accountValue
            val ddUsd = peak - s.accountValue
            if (ddUsd > maxUsd) maxUsd = ddUsd
            if (peak.signum() > 0) {
                val ddPct = ddUsd.divide(peak, ratioScale, RoundingMode.HALF_UP)
                if (ddPct > maxPct) maxPct = ddPct
            }
        }
        return maxUsd to maxPct
    }
}

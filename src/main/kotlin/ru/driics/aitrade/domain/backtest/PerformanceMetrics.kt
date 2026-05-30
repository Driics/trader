package ru.driics.aitrade.domain.backtest

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Aggregate performance of a backtest, computed purely from the closed [SimTrade]s and the equity
 * curve. Percentages are whole-number percents (e.g. 12.50 = 12.5%). Sharpe is intentionally omitted
 * from v1 — annualization assumptions invite a misleading number.
 */
data class PerformanceMetrics(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: BigDecimal,
    val totalReturnPct: BigDecimal,
    val maxDrawdownPct: BigDecimal,
    val profitFactor: BigDecimal?,   // null when there are no losing trades (undefined / "infinite")
    val grossProfitUsd: BigDecimal,
    val grossLossUsd: BigDecimal,    // positive magnitude of losing trades
    val netPnlUsd: BigDecimal,
) {
    companion object {
        private val HUNDRED = BigDecimal(100)

        /**
         * @param startingEquityUsd equity at t0, before any trade.
         * @param equityCurve marked equity at each step, chronological; the last point is final equity.
         * @param trades closed round-trip trades.
         */
        fun from(
            startingEquityUsd: BigDecimal,
            equityCurve: List<BigDecimal>,
            trades: List<SimTrade>,
        ): PerformanceMetrics {
            val wins = trades.filter { it.pnlUsd.signum() > 0 }
            val losses = trades.filter { it.pnlUsd.signum() < 0 }
            val grossProfit = wins.fold(BigDecimal.ZERO) { acc, t -> acc + t.pnlUsd }
            val grossLoss = losses.fold(BigDecimal.ZERO) { acc, t -> acc - t.pnlUsd } // magnitude (pnl < 0)
            val net = trades.fold(BigDecimal.ZERO) { acc, t -> acc + t.pnlUsd }

            val finalEquity = equityCurve.lastOrNull() ?: startingEquityUsd
            val totalReturnPct = if (startingEquityUsd.signum() == 0) {
                BigDecimal.ZERO
            } else {
                (finalEquity - startingEquityUsd).divide(startingEquityUsd, 6, RoundingMode.HALF_UP) * HUNDRED
            }

            val winRatePct = if (trades.isEmpty()) {
                BigDecimal.ZERO
            } else {
                BigDecimal(wins.size).divide(BigDecimal(trades.size), 6, RoundingMode.HALF_UP) * HUNDRED
            }

            val profitFactor = if (grossLoss.signum() == 0) {
                null
            } else {
                grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP)
            }

            return PerformanceMetrics(
                trades = trades.size,
                wins = wins.size,
                losses = losses.size,
                winRatePct = winRatePct.setScale(2, RoundingMode.HALF_UP),
                totalReturnPct = totalReturnPct.setScale(2, RoundingMode.HALF_UP),
                maxDrawdownPct = maxDrawdownPct(equityCurve).setScale(2, RoundingMode.HALF_UP),
                profitFactor = profitFactor,
                grossProfitUsd = grossProfit,
                grossLossUsd = grossLoss,
                netPnlUsd = net,
            )
        }

        /** Largest peak-to-trough decline of the equity curve, as a positive percent. */
        internal fun maxDrawdownPct(equityCurve: List<BigDecimal>): BigDecimal {
            if (equityCurve.isEmpty()) return BigDecimal.ZERO
            var peak = equityCurve.first()
            var maxDd = BigDecimal.ZERO
            for (e in equityCurve) {
                if (e > peak) peak = e
                if (peak.signum() > 0) {
                    val dd = (peak - e).divide(peak, 6, RoundingMode.HALF_UP)
                    if (dd > maxDd) maxDd = dd
                }
            }
            return maxDd * HUNDRED
        }
    }
}

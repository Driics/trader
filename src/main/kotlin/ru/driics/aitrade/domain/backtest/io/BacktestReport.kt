package ru.driics.aitrade.domain.backtest.io

import ru.driics.aitrade.domain.backtest.engine.BacktestResult
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Renders a [BacktestResult] as a plain-text report. Pure (no I/O), so it is unit-testable; the harness
 * writes the returned string to stdout / a file. Always prints the [BacktestResult.omissions] and the
 * rejection tally so a reader never mistakes the headline return for net-of-everything or assumes every
 * signal was traded.
 */
object BacktestReport {

    fun render(
        startingEquityUsd: BigDecimal,
        barCount: Int,
        result: BacktestResult,
        extraNotes: List<String> = emptyList(),
    ): String {
        val m = result.metrics
        val finalEquity = result.equityCurve.lastOrNull() ?: startingEquityUsd
        val sb = StringBuilder()

        sb.appendLine("==== Backtest report ====")
        sb.appendLine("symbol         : ${result.symbol}")
        sb.appendLine("strategy       : ${result.strategyName}")
        sb.appendLine("bars           : $barCount")
        sb.appendLine("starting equity: ${usd(startingEquityUsd)}")
        sb.appendLine("final equity   : ${usd(finalEquity)}")
        sb.appendLine("-- performance --")
        sb.appendLine("total return   : ${pct(m.totalReturnPct)}")
        sb.appendLine("max drawdown   : ${pct(m.maxDrawdownPct)}")
        sb.appendLine("net PnL        : ${usd(m.netPnlUsd)}")
        sb.appendLine("gross profit   : ${usd(m.grossProfitUsd)}")
        sb.appendLine("gross loss     : ${usd(m.grossLossUsd)}")
        sb.appendLine("trades         : ${m.trades}  (wins ${m.wins} / losses ${m.losses})")
        sb.appendLine("win rate       : ${pct(m.winRatePct)}")
        sb.appendLine("profit factor  : ${m.profitFactor?.setScale(2, RoundingMode.HALF_UP) ?: "n/a (no losses)"}")
        sb.appendLine("-- entries declined --")
        sb.appendLine("no stop        : ${result.rejections.noStop}")
        sb.appendLine("gapped through : ${result.rejections.gappedThroughBracket}")
        sb.appendLine("unaffordable   : ${result.rejections.unaffordable}")
        sb.appendLine("already open   : ${result.rejections.alreadyOpenForSymbol}")
        sb.appendLine("-- NOT modelled (curve is gross of these) --")
        result.omissions.forEach { sb.appendLine("  - $it") }
        if (extraNotes.isNotEmpty()) {
            sb.appendLine("-- notes --")
            extraNotes.forEach { sb.appendLine("  - $it") }
        }
        return sb.toString()
    }

    private fun usd(v: BigDecimal): String = "$" + v.setScale(2, RoundingMode.HALF_UP).toPlainString()
    private fun pct(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
}

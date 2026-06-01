package ru.driics.aitrade.domain.backtest.analysis

import ru.driics.aitrade.domain.backtest.core.Bar
import ru.driics.aitrade.domain.backtest.engine.BacktestConfig
import ru.driics.aitrade.domain.backtest.engine.BacktestEngine
import ru.driics.aitrade.domain.strategy.Strategy
import java.math.BigDecimal
import java.math.RoundingMode

/** One row of a parameter sweep — the headline metrics for a single strategy variant. */
data class SweepResult(
    val label: String,
    val totalReturnPct: BigDecimal,
    val maxDrawdownPct: BigDecimal,
    val trades: Int,
    val winRatePct: BigDecimal,
    val profitFactor: BigDecimal?,
    val netPnlUsd: BigDecimal,
)

/**
 * Runs many strategy variants over the SAME candles + config and renders a comparison table — the cheap
 * way to ask "is any parameterization profitable?" without re-reading N full reports. Pure; the engine is
 * deterministic, so the table is reproducible. Beware reading the top row as "the answer": a grid winner
 * on one window is the textbook overfit — confirm out-of-sample before trusting it.
 */
object ParameterSweep {

    fun run(
        symbol: String,
        bars: List<Bar>,
        config: BacktestConfig,
        variants: List<Pair<String, Strategy>>,
    ): List<SweepResult> = variants.map { (label, strategy) ->
        val m = BacktestEngine(strategy, config).run(symbol, bars).metrics
        SweepResult(label, m.totalReturnPct, m.maxDrawdownPct, m.trades, m.winRatePct, m.profitFactor, m.netPnlUsd)
    }

    /** Renders the results best-return-first as a fixed-width table. */
    fun renderTable(rows: List<SweepResult>): String {
        val sb = StringBuilder()
        sb.appendLine(
            "variant".padEnd(30) + "return%".padStart(9) + "maxDD%".padStart(9) +
                "trades".padStart(8) + "win%".padStart(8) + "PF".padStart(7) + "netPnl$".padStart(12),
        )
        sb.appendLine("-".repeat(83))
        rows.sortedByDescending { it.totalReturnPct }.forEach { r ->
            sb.appendLine(
                r.label.padEnd(30) +
                    p(r.totalReturnPct).padStart(9) +
                    p(r.maxDrawdownPct).padStart(9) +
                    r.trades.toString().padStart(8) +
                    p(r.winRatePct).padStart(8) +
                    (r.profitFactor?.let { p(it) } ?: "n/a").padStart(7) +
                    p(r.netPnlUsd).padStart(12),
            )
        }
        return sb.toString()
    }

    private fun p(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

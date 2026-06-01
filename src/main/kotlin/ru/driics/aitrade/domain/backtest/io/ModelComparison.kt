package ru.driics.aitrade.domain.backtest.io

import ru.driics.aitrade.domain.backtest.core.PerformanceMetrics
import ru.driics.aitrade.domain.backtest.engine.BacktestConfig
import java.math.BigDecimal
import java.math.RoundingMode

/** One model's replayed performance over the shared candle series. */
data class ModelResult(
    val label: String,
    val metrics: PerformanceMetrics,
    val matched: Int,
    val recorded: Int,
)

/**
 * Side-by-side comparison of several recorded AI decision logs replayed over the SAME candles + config,
 * ranked by total return. Offline + pure (no LLM): each model is recorded separately (live, costs money),
 * then this replays them deterministically so the only variable is the model's decisions. All models run
 * through the shared per-trade risk cap ([BacktestConfig.riskPerTradePct]), so the numbers reflect the
 * sizing production would actually use — not a model's self-reported (uncapped) quantity.
 */
data class ModelComparison(
    val symbol: String,
    val startingEquityUsd: BigDecimal,
    val bars: Int,
    val rows: List<ModelResult>,   // ranked best -> worst by total return
) {
    fun render(): String {
        val sb = StringBuilder()
        sb.appendLine("==== Model comparison — $symbol, $bars bars, start ${usd(startingEquityUsd)} ====")
        sb.appendLine(tableRow("#", "model", "return", "net PnL", "trades", "win%", "maxDD", "PF", "matched"))
        rows.forEachIndexed { i, r ->
            val m = r.metrics
            sb.appendLine(
                tableRow(
                    "${i + 1}",
                    r.label.take(COL_LABEL),
                    signedPct(m.totalReturnPct),
                    usd(m.netPnlUsd),
                    "${m.trades}",
                    "${m.winRatePct.toPlainString()}%",
                    "${m.maxDrawdownPct.toPlainString()}%",
                    m.profitFactor?.toPlainString() ?: "n/a",
                    "${r.matched}/${r.recorded}",
                ),
            )
        }
        sb.appendLine("-- ranked by total return; equity curve is gross of funding + slippage --")
        // Surface any log that doesn't fully line up with the candles: a 0-match log replays as all-HOLD
        // (a misleading flat 0% that looks like "chose not to trade"), and a PARTIAL match means the row
        // is computed from only a fraction of the model's decisions — not comparable to a full-match row.
        rows.filter { it.recorded > 0 && it.matched < it.recorded }.forEach {
            val detail = if (it.matched == 0) {
                "0/${it.recorded} decisions matched — log and candles do not line up"
            } else {
                "only ${it.matched}/${it.recorded} decisions matched — result may not be representative"
            }
            sb.appendLine("! ${it.label}: $detail")
        }
        return sb.toString()
    }

    private companion object {
        const val COL_LABEL = 20
        private val WIDTHS = intArrayOf(3, COL_LABEL, 8, 12, 7, 7, 7, 6, 9)

        fun tableRow(vararg cols: String): String =
            cols.mapIndexed { i, c -> c.padEnd(WIDTHS.getOrElse(i) { 8 }) }.joinToString(" ").trimEnd()

        fun usd(v: BigDecimal): String {
            val abs = v.abs().setScale(2, RoundingMode.HALF_UP).toPlainString()
            return if (v.signum() < 0) "-\$$abs" else "\$$abs"
        }

        fun signedPct(v: BigDecimal): String {
            val s = v.setScale(2, RoundingMode.HALF_UP)
            return (if (s.signum() > 0) "+" else "") + s.toPlainString() + "%"
        }
    }
}

/**
 * Replays each `label -> decisionJsonl` over [candleJsonl] (same symbol + [config]) and ranks the models by
 * total return. Reuses [BacktestRunner.replayAi] per model so every run shares the engine's sizing, risk
 * cap, and no-look-ahead guarantees — the only variable is the decision log.
 */
fun compareModels(
    candleJsonl: String,
    symbol: String,
    minConfidence: BigDecimal,
    config: BacktestConfig,
    models: Map<String, String>,
): ModelComparison {
    val bars = JsonlCandleParser.parse(candleJsonl).size
    val rows = models.map { (label, decisionJsonl) ->
        val outcome = BacktestRunner.replayAi(candleJsonl, decisionJsonl, symbol, minConfidence, config)
        ModelResult(
            label = label,
            metrics = outcome.result.metrics,
            matched = outcome.matchStats.matched,
            recorded = outcome.matchStats.recorded,
        )
    }.sortedByDescending { it.metrics.totalReturnPct }
    return ModelComparison(symbol, config.startingEquityUsd, bars, rows)
}

package ru.driics.aitrade.domain.backtest.analysis

import ru.driics.aitrade.domain.backtest.core.Bar
import ru.driics.aitrade.domain.backtest.core.PerformanceMetrics
import ru.driics.aitrade.domain.backtest.engine.BacktestConfig
import ru.driics.aitrade.domain.backtest.engine.BacktestEngine
import ru.driics.aitrade.domain.backtest.engine.BacktestResult
import ru.driics.aitrade.domain.strategy.Strategy
import java.math.BigDecimal
import java.math.RoundingMode

/** A single in-sample/out-of-sample split: the train-winner re-measured on the held-out tail. */
data class SplitTestResult(
    val trainBars: Int,
    val testBars: Int,
    val chosenLabel: String,
    val trainReturnPct: BigDecimal,
    val testReturnPct: BigDecimal,
    val testTrades: Int,
    val testProfitFactor: BigDecimal?,
)

/** One anchored walk-forward fold: best-on-train applied to the next unseen block. */
data class WalkForwardFold(
    val fold: Int,
    val trainBars: Int,
    val testBars: Int,
    val chosenLabel: String,
    val trainReturnPct: BigDecimal,
    val testReturnPct: BigDecimal,
    val testTrades: Int,
    val testProfitFactor: BigDecimal?,
)

/**
 * Out-of-sample validation — the antidote to a grid winner that only "won" because it was selected and
 * judged on the same data. These split the timeline so the judging data is unseen at selection time:
 *  - [splitTest]: one in-sample/out-of-sample cut (pick best on the front, measure on the held-out tail).
 *  - [walkForward]: anchored folds (pick best on bars[0,k), measure on the next block) — also reveals
 *    whether the SAME variant keeps winning (stable = more likely a real edge; jumping = overfit).
 *
 * A test block is warmed on the real [BacktestConfig.indicatorLookback] bars immediately before it, so it
 * is not cold-started; only its own trades count toward its score.
 */
object WalkForward {

    fun splitTest(
        symbol: String,
        bars: List<Bar>,
        config: BacktestConfig,
        variants: List<Pair<String, Strategy>>,
        trainFraction: Double = 0.7,
        selectBy: (PerformanceMetrics) -> BigDecimal = { it.totalReturnPct },
    ): SplitTestResult {
        val trainEnd = (bars.size * trainFraction).toInt()
        require(trainEnd in 1 until bars.size) { "train fraction $trainFraction yields no train/test split" }
        val (label, strat, trainM) = pickBest(symbol, bars, 0, trainEnd, config, variants, selectBy)
        val testM = segment(symbol, bars, trainEnd, bars.size, config, strat).metrics
        return SplitTestResult(
            trainBars = trainEnd, testBars = bars.size - trainEnd, chosenLabel = label,
            trainReturnPct = trainM.totalReturnPct, testReturnPct = testM.totalReturnPct,
            testTrades = testM.trades, testProfitFactor = testM.profitFactor,
        )
    }

    fun walkForward(
        symbol: String,
        bars: List<Bar>,
        config: BacktestConfig,
        variants: List<Pair<String, Strategy>>,
        folds: Int,
        selectBy: (PerformanceMetrics) -> BigDecimal = { it.totalReturnPct },
    ): List<WalkForwardFold> {
        require(folds >= 1) { "folds must be >= 1" }
        val segLen = bars.size / (folds + 1)
        require(segLen > config.indicatorLookback) {
            "not enough bars (${bars.size}) for $folds folds: each segment must exceed indicatorLookback ${config.indicatorLookback}"
        }
        return (1..folds).map { f ->
            val trainEnd = f * segLen
            val testEnd = if (f == folds) bars.size else (f + 1) * segLen
            val (label, strat, trainM) = pickBest(symbol, bars, 0, trainEnd, config, variants, selectBy)
            val testM = segment(symbol, bars, trainEnd, testEnd, config, strat).metrics
            WalkForwardFold(
                fold = f, trainBars = trainEnd, testBars = testEnd - trainEnd, chosenLabel = label,
                trainReturnPct = trainM.totalReturnPct, testReturnPct = testM.totalReturnPct,
                testTrades = testM.trades, testProfitFactor = testM.profitFactor,
            )
        }
    }

    private fun pickBest(
        symbol: String, bars: List<Bar>, from: Int, to: Int, config: BacktestConfig,
        variants: List<Pair<String, Strategy>>, selectBy: (PerformanceMetrics) -> BigDecimal,
    ): Triple<String, Strategy, PerformanceMetrics> =
        variants
            .map { (label, strat) -> Triple(label, strat, segment(symbol, bars, from, to, config, strat).metrics) }
            .maxByOrNull { selectBy(it.third) }!!

    /** Backtests bars[fromIdx, toIdx), warming indicators on the lookback bars immediately before fromIdx. */
    private fun segment(
        symbol: String, bars: List<Bar>, fromIdx: Int, toIdx: Int, config: BacktestConfig, strategy: Strategy,
    ): BacktestResult {
        val segStart = maxOf(0, fromIdx - config.indicatorLookback)
        val warmup = maxOf(config.warmupBars, fromIdx - segStart)
        return BacktestEngine(strategy, config.copy(warmupBars = warmup)).run(symbol, bars.subList(segStart, toIdx))
    }

    fun render(split: SplitTestResult, folds: List<WalkForwardFold>): String {
        val sb = StringBuilder()
        sb.appendLine("==== Out-of-sample validation ====")
        sb.appendLine("-- in-sample/out-of-sample split --")
        sb.appendLine("train ${split.trainBars} bars -> winner: ${split.chosenLabel}")
        sb.appendLine("  in-sample return : ${pct(split.trainReturnPct)}")
        sb.appendLine("  OUT-OF-SAMPLE    : ${pct(split.testReturnPct)}  (test ${split.testBars} bars, ${split.testTrades} trades, PF ${pf(split.testProfitFactor)})")
        sb.appendLine("  verdict: ${if (split.testReturnPct.signum() > 0) "held up out-of-sample" else "COLLAPSED out-of-sample (likely overfit)"}")
        sb.appendLine("-- anchored walk-forward --")
        sb.appendLine("fold".padEnd(5) + "train".padStart(7) + "test".padStart(6) + "  winner".padEnd(26) + "train%".padStart(9) + "test%".padStart(9) + "trades".padStart(7) + "PF".padStart(7))
        folds.forEach { f ->
            sb.appendLine(
                f.fold.toString().padEnd(5) + f.trainBars.toString().padStart(7) + f.testBars.toString().padStart(6) +
                    ("  " + f.chosenLabel).padEnd(26) + pct(f.trainReturnPct).padStart(9) + pct(f.testReturnPct).padStart(9) +
                    f.testTrades.toString().padStart(7) + pf(f.testProfitFactor).padStart(7),
            )
        }
        val positive = folds.count { it.testReturnPct.signum() > 0 }
        val stable = folds.map { it.chosenLabel }.distinct().size == 1
        sb.appendLine("OOS folds positive: $positive/${folds.size}   winner stable across folds: ${if (stable) "yes" else "NO (winner jumps -> overfit signal)"}")
        return sb.toString()
    }

    private fun pct(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
    private fun pf(v: BigDecimal?): String = v?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "n/a"
}

package ru.driics.aitrade.domain.backtest

import ru.driics.aitrade.domain.strategy.RecordedAiStrategy
import ru.driics.aitrade.domain.strategy.RsiReversionStrategy
import ru.driics.aitrade.domain.strategy.Strategy
import java.math.BigDecimal

/**
 * Pure orchestration over [JsonlCandleParser] + [BacktestEngine] — takes a JSONL string (not a file), so
 * it stays testable without I/O. The harness (a env-gated test) adds the file read and report write.
 *
 * [defaultConfig] ships BTC-USDT-SWAP contract specs and middle-of-the-road cost/risk knobs; for other
 * instruments, pass a tailored [BacktestConfig] (the specs MUST match the symbol — see [InstrumentSpec]).
 */
object BacktestRunner {

    /** BTC-USDT-SWAP defaults: ctVal 0.01 BTC/contract (coin-quote branch), lot/min 0.1 contracts. */
    fun defaultConfig(
        symbol: String,
        startingEquityUsd: BigDecimal = BigDecimal("10000"),
    ): BacktestConfig = BacktestConfig(
        startingEquityUsd = startingEquityUsd,
        takerFeePct = BigDecimal("0.0005"),      // 5 bps OKX taker
        marginBufferPct = BigDecimal("0.05"),
        riskPerTradePct = BigDecimal("0.01"),    // risk 1% of equity per trade
        warmupBars = 30,                          // enough history for RSI/MACD/EMA to be meaningful
        intradayWindow = 200,
        indicatorLookback = 300,                  // bounds per-bar indicator cost -> O(n) over the series
        instruments = mapOf(
            symbol to InstrumentSpec(
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("0.1"),
                minSz = BigDecimal("0.1"),
            ),
        ),
        minLev = 5,
        maxLev = 40,
    )

    fun run(
        jsonl: String,
        symbol: String,
        strategy: Strategy = RsiReversionStrategy(),
        config: BacktestConfig = defaultConfig(symbol),
    ): BacktestResult {
        val bars = JsonlCandleParser.parse(jsonl)
        return BacktestEngine(strategy, config).run(symbol, bars)
    }

    /**
     * Backtests a previously-recorded AI decision log against candles — pure (`String, String -> outcome`)
     * so it is testable without an LLM. [minConfidence] gates at replay (sweepable). The returned
     * [AiReplayOutcome.matchStats] guards the silent timestamp-mismatch trap (recorded-but-unmatched
     * decisions → all-HOLD → a misleading flat curve).
     */
    fun replayAi(
        candleJsonl: String,
        decisionJsonl: String,
        symbol: String,
        minConfidence: BigDecimal = BigDecimal.ZERO,
        config: BacktestConfig = defaultConfig(symbol),
    ): AiReplayOutcome {
        val bars = JsonlCandleParser.parse(candleJsonl)
        val decisions = AiDecisionLog.parse(decisionJsonl)
        val strategy = RecordedAiStrategy(decisions, minConfidence)
        val result = BacktestEngine(strategy, config).run(symbol, bars)
        val match = AiDecisionLog.matchStats(strategy.recordedTimestamps, bars.map { it.timestampMs })
        return AiReplayOutcome(result, match)
    }
}

/** A recorded-AI backtest plus the decision↔candle match-rate that proves the two files lined up. */
data class AiReplayOutcome(
    val result: BacktestResult,
    val matchStats: MatchStats,
)

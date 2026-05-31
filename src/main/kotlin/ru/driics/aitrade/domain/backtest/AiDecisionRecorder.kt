package ru.driics.aitrade.domain.backtest

import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiAnalysisResponse
import ru.driics.aitrade.domain.model.AiTradeDecisionMap
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.strategy.RecordedDecision
import ru.driics.aitrade.domain.strategy.toRecordedDecision
import java.math.BigDecimal

/**
 * Walks a candle series and asks the live AI flow for a decision at each invocation point, producing a
 * deterministic [RecordedDecision] log to replay later (see `RecordedAiStrategy`).
 *
 * Decoupled from Spring via function deps — [buildPrompt] (wraps the prompt builder), [analyze] (wraps
 * the paid, non-deterministic [ru.driics.aitrade.domain.ports.AiAnalysisPort] call) and [parseDecisions]
 * (wraps the schema validator) — so the loop (cadence, bounded no-look-ahead window, flat-state, mapping)
 * is unit-testable with fakes; only the real [analyze] needs network at run time.
 *
 * NO LOOK-AHEAD: each `MarketState` is built from the SAME bounded trailing window the engine uses, so the
 * AI never sees a future bar. FLAT STATE (see `RecordedAiStrategy` caveat): no open positions and start
 * equity — consistent with what the engine can execute (entry under fixed brackets). [cadenceBars] thins
 * invocations to match the live cadence and bound LLM cost (every bar is usually far more than live).
 */
class AiDecisionRecorder(
    private val symbol: String,
    private val config: BacktestConfig,
    private val buildPrompt: (MarketState) -> String,
    private val analyze: suspend (String) -> AiAnalysisResponse,
    private val parseDecisions: (String) -> AiTradeDecisionMap?,
    private val cadenceBars: Int = 1,
    private val maxInvocations: Int? = null,
) {
    suspend fun record(
        bars: List<Bar>,
        onSkip: (barIndex: Int, reason: String) -> Unit = { _, _ -> },
    ): List<RecordedDecision> {
        require(cadenceBars >= 1) { "cadenceBars must be >= 1" }
        var invocations = 0
        val account = AccountInfo(
            totalReturn = BigDecimal.ZERO,
            availableCash = config.startingEquityUsd,
            accountValue = config.startingEquityUsd,
        )
        val out = ArrayList<RecordedDecision>()

        for (i in bars.indices) {
            if (i < config.warmupBars) continue
            if ((i - config.warmupBars) % cadenceBars != 0) continue
            if (maxInvocations != null && invocations >= maxInvocations) break
            invocations++

            val from = maxOf(0, i + 1 - config.indicatorLookback)
            val state = MarketStateBuilder.build(
                symbol = symbol,
                bars = bars.subList(from, i + 1),
                stepIndex = i,
                account = account,
                positions = emptyList(),
                intradayWindow = config.intradayWindow,
            )

            val response = analyze(buildPrompt(state))
            if (!response.isSuccess) {
                onSkip(i, "ai failure: ${response.errorMessage}")
                continue
            }
            val decisions = parseDecisions(response.response)
            if (decisions == null) {
                onSkip(i, "unparseable / rejected response")
                continue
            }
            // Single-symbol recording: prefer the symbol key, else the first envelope (AI keys by coin).
            val args = decisions[symbol]?.args ?: decisions.values.firstOrNull()?.args
            if (args == null) {
                onSkip(i, "no decision in response")
                continue
            }
            out += args.toRecordedDecision(symbol, bars[i].timestampMs)
        }
        return out
    }
}

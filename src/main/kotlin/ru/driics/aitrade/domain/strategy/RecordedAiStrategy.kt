package ru.driics.aitrade.domain.strategy

import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

/**
 * One AI decision frozen at a bar's timestamp — the unit of an AI decision log. Mirrors the live
 * [AiTradeSignalArgs] fields that matter to the simulation (profit_target → [takeProfit]).
 */
data class RecordedDecision(
    val timestampMs: Long,
    val symbol: String,
    val signal: AiSignal,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val leverage: Int? = null,
    val riskUsd: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val confidence: BigDecimal? = null,
) {
    fun toStrategyDecision(): StrategyDecision =
        StrategyDecision(symbol, signal, stopLoss, takeProfit, leverage, riskUsd, quantity, confidence)
}

/** Maps a live AI signal to a recorded decision stamped with the backtest [symbol] and bar timestamp. */
fun AiTradeSignalArgs.toRecordedDecision(symbol: String, timestampMs: Long): RecordedDecision =
    RecordedDecision(
        timestampMs = timestampMs,
        symbol = symbol,
        signal = signal,
        stopLoss = stopLoss,
        takeProfit = profitTarget,
        leverage = leverage,
        riskUsd = riskUsd,
        quantity = quantity,
        confidence = confidence,
    )

/**
 * Replays AI decisions recorded offline (see `AiDecisionRecorder`) as a deterministic [Strategy]: at each
 * bar it returns the decision recorded for that bar's timestamp, or nothing. The expensive,
 * non-deterministic LLM calls happen ONCE at record time; replay is free and reproducible — so a
 * parameter sweep (e.g. [minConfidence]) costs nothing extra over a single recording.
 *
 * [minConfidence] gates exactly as the live `ExecuteAiDecisionsUseCase` does (a missing confidence counts
 * as 0; below the threshold → skipped), but applied at REPLAY so the threshold can be swept.
 *
 * CAVEAT — read with every result: this validates the AI's ENTRY signals under fixed SL/TP brackets only.
 * The recorder feeds the AI a flat, no-open-positions, start-equity snapshot, and the engine cannot
 * early-close / scale-in / invalidate — so the AI's hold/close/invalidation logic and its
 * drawdown-aware risk sizing are NOT exercised here. A profitable replay is necessary, not sufficient.
 */
class RecordedAiStrategy(
    recorded: List<RecordedDecision>,
    private val minConfidence: BigDecimal = BigDecimal.ZERO,
) : Strategy {

    private val byTimestamp: Map<Long, StrategyDecision> =
        recorded.associate { it.timestampMs to it.toStrategyDecision() }

    /** Timestamps carrying a recorded decision — used to compute the replay match-rate vs the candle file. */
    val recordedTimestamps: Set<Long> get() = byTimestamp.keys

    override val name: String = "recorded-ai(minConf=$minConfidence)"

    override fun decide(state: MarketState): List<StrategyDecision> {
        val decision = byTimestamp[state.timestamp] ?: return emptyList()
        val confidence = decision.confidence ?: BigDecimal.ZERO
        if (confidence < minConfidence) return emptyList()
        return listOf(decision)
    }
}

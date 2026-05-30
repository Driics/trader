package ru.driics.aitrade.domain.strategy

import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

/**
 * One decision a strategy emits for a single symbol on a single market snapshot. Intentionally lean
 * and strategy-agnostic (no AI-specific fields like justification) so the same port serves a rule
 * strategy, the future AI strategy, and the backtest engine alike. A HOLD signal means "no action".
 */
data class StrategyDecision(
    val symbol: String,
    val signal: AiSignal,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val leverage: Int? = null,
    val riskUsd: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val confidence: BigDecimal? = null,
)

/**
 * The seam that breaks the hardcoded AI decision path: anything that turns a [MarketState] into a
 * set of trade decisions. Deterministic implementations (rule strategies) are backtest-capable; the
 * live AI flow will be adapted behind this port later.
 *
 * [decide] MUST be pure for backtest-capable strategies — no I/O, no clock, no randomness — so a
 * backtest run is reproducible and free of look-ahead (it sees only the snapshot it is handed).
 */
interface Strategy {
    val name: String
    fun decide(state: MarketState): List<StrategyDecision>
}

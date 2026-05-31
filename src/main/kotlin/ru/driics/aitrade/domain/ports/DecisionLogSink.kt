package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.AiTradeDecisionMap

/**
 * Persists the AI's per-cycle decisions for audit/history.
 *
 * Implementations MUST be fail-safe — a sink error must never break a trading cycle.
 *
 * [timestampMs] is the cycle's wall-clock market-snapshot time, NOT a candle-bar boundary, so the output
 * is an audit trail (what the AI decided and when), not a drop-in `RecordedAiStrategy` replay input —
 * that replays by exact bar-timestamp lookup, so a wall-clock stamp would match no bars. The rows share
 * the `RecordedDecision` shape, so the history is structurally convertible to a replay log later (snap
 * the timestamps to a bar grid first).
 */
interface DecisionLogSink {
    fun record(decisions: AiTradeDecisionMap, timestampMs: Long)
}

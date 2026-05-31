package ru.driics.aitrade.domain.backtest.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ru.driics.aitrade.domain.strategy.RecordedDecision

/**
 * Pure JSONL (de)serialization of recorded AI decisions — one JSON object per line — symmetric with
 * [JsonlCandleParser]. Persisting the recording lets the expensive, non-deterministic LLM pass run once
 * (network, user-run) while the deterministic replay/backtest runs offline as many times as needed.
 */
object AiDecisionLog {

    private val mapper = jacksonObjectMapper()

    fun serialize(decisions: List<RecordedDecision>): String =
        decisions.joinToString("\n") { mapper.writeValueAsString(it) }

    fun parse(jsonl: String): List<RecordedDecision> =
        jsonl.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { mapper.readValue<RecordedDecision>(it) }
            .toList()

    /**
     * How many recorded decisions actually land on a candle in [barTimestamps]. A near-zero [MatchStats.matched]
     * means the decision log and the candle file don't line up (different instrument / timeframe / window) —
     * which would otherwise masquerade as "the AI never traded". Surface this with every AI replay.
     */
    fun matchStats(recordedTimestamps: Set<Long>, barTimestamps: Collection<Long>): MatchStats {
        val barSet = barTimestamps.toHashSet()
        val matched = recordedTimestamps.count { it in barSet }
        return MatchStats(recorded = recordedTimestamps.size, matched = matched)
    }
}

data class MatchStats(val recorded: Int, val matched: Int) {
    val unmatched: Int get() = recorded - matched
    val matchRatePct: Int get() = if (recorded == 0) 0 else (matched * 100) / recorded
}

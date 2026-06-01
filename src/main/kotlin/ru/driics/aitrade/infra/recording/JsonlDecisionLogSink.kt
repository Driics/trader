package ru.driics.aitrade.infra.recording

import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.backtest.ai.AiDecisionLog
import ru.driics.aitrade.domain.model.AiTradeDecisionMap
import ru.driics.aitrade.domain.ports.DecisionLogSink
import ru.driics.aitrade.domain.strategy.toRecordedDecision
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Appends each cycle's AI decisions to a JSONL file — one `RecordedDecision` per line, the same shape
 * [AiDecisionLog] reads — so live AI behavior is auditable and the history is structurally convertible
 * to a replay log. Fail-safe: any IO error is logged and swallowed so the trading cycle is unaffected.
 *
 * Known limitation: append-only, so the file grows unbounded — rotate/prune it externally if needed.
 */
class JsonlDecisionLogSink(private val path: Path) : DecisionLogSink {

    private companion object {
        val log = logger<JsonlDecisionLogSink>()
    }

    private val lock = Any()

    override fun record(decisions: AiTradeDecisionMap, timestampMs: Long) {
        if (decisions.isEmpty()) return
        try {
            val recorded = decisions.map { (symbol, envelope) ->
                envelope.args.toRecordedDecision(symbol, timestampMs)
            }
            val block = AiDecisionLog.serialize(recorded) + "\n"
            synchronized(lock) {
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, block, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to persist AI decision log to $path (cycle continues)" }
        }
    }
}

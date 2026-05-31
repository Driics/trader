package ru.driics.aitrade.domain.backtest.io

import com.fasterxml.jackson.databind.ObjectMapper
import ru.driics.aitrade.domain.backtest.core.Bar
import java.math.BigDecimal

/**
 * Pure parser: a JSONL string of OKX candle arrays → an ascending [Bar] series. Kept a plain
 * `String -> List<Bar>` function (no file I/O, no Spring) so it stays unit-testable in isolation; the
 * file/network plumbing lives in the harness and the documented fetch recipe (docs/backtest-howto.md).
 *
 * Each non-blank, non-`#`-comment line must be a JSON array in OKX `/market/candles` order:
 * `[ts, open, high, low, close, vol, ...]` (values may be JSON strings or numbers). Extra trailing
 * elements (volCcy, confirm, ...) are ignored. Lines are sorted ascending by timestamp, since OKX
 * returns candles newest-first.
 *
 * Malformed JSON or a too-short array throws — a backtest on corrupt data is worse than no backtest.
 */
object JsonlCandleParser {

    private val mapper = ObjectMapper()

    fun parse(jsonl: String): List<Bar> =
        jsonl.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { parseLine(it) }
            .sortedBy { it.timestampMs }
            .toList()

    private fun parseLine(line: String): Bar {
        val node = mapper.readTree(line)
        require(node != null && node.isArray && node.size() >= 5) {
            "Expected an OKX candle array [ts,o,h,l,c,...], got: $line"
        }
        return Bar(
            timestampMs = node.get(0).asText().toLong(),
            open = BigDecimal(node.get(1).asText()),
            high = BigDecimal(node.get(2).asText()),
            low = BigDecimal(node.get(3).asText()),
            close = BigDecimal(node.get(4).asText()),
            volume = if (node.size() > 5) node.get(5).asText().toBigDecimalOrNull() ?: BigDecimal.ZERO
            else BigDecimal.ZERO,
        )
    }
}

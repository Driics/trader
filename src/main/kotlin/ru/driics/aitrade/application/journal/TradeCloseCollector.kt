package ru.driics.aitrade.application.journal

import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-cycle trade-close capture (fail-safe). Rehydrates a high-water mark from the journal on first use,
 * then each [collect] fetches closes newer than the mark and journals them. Dedup is belt-and-suspenders:
 * the in-memory mark bounds the fetch window, and `recordClose` is idempotent on `pos_id`, so a restart
 * (mark reseeded from MAX(close_time)) can neither double-count nor gap. Any error is swallowed.
 */
class TradeCloseCollector(
    private val source: ClosedPositionsPort,
    private val journal: TradeJournalPort,
    private val query: TradeJournalQueryPort,
    private val clock: Clock,
    private val mode: TradingMode,
    private val demo: Boolean,
) {
    private companion object { val log = logger<TradeCloseCollector>() }

    private val highWater = AtomicLong(Long.MIN_VALUE) // MIN = not yet rehydrated

    suspend fun collect() {
        try {
            if (highWater.get() == Long.MIN_VALUE) {
                highWater.set(query.latestCloseTimeMs() ?: 0L)
            }
            val since = highWater.get()
            val closes = source.closedSince(since)
            if (closes.isEmpty()) return
            val now = clock.instant().toEpochMilli()
            var maxClose = since
            for (cp in closes) {
                journal.recordClose(cp.toJournaled(now))
                if (cp.closeTimeMs > maxClose) maxClose = cp.closeTimeMs
            }
            highWater.set(maxClose)
            log.info { "Captured ${closes.size} trade close(s); high-water=$maxClose" }
        } catch (e: Exception) {
            log.warn(e) { "Trade-close capture failed (cycle continues)" }
        }
    }

    private fun ClosedPosition.toJournaled(nowMs: Long) = JournaledClose(
        recordedAtMs = nowMs, posId = posId, instId = instId,
        symbol = instId.substringBefore("-"), side = side, realizedPnl = realizedPnl,
        openTimeMs = openTimeMs, closeTimeMs = closeTimeMs, mode = mode, demo = demo,
    )
}

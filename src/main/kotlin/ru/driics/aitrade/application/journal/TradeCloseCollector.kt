package ru.driics.aitrade.application.journal

import kotlinx.coroutines.CancellationException
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.CloseWriteResult
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-cycle trade-close capture (fail-safe). Rehydrates a high-water mark from the journal on first use,
 * then each [collect] fetches closes newer than the mark and journals them. Dedup is belt-and-suspenders:
 * the in-memory mark bounds the fetch window, and `recordClose` is idempotent on `pos_id`, so a restart
 * (mark reseeded from MAX(close_time)) can neither double-count nor gap.
 *
 * The high-water mark is advanced ONLY over a confirmed contiguous (oldest-first) prefix of closes.
 * On the first FAILED write, capture stops and the mark is not advanced past that close, so the failed
 * close is retried next cycle and never silently skipped (gap-proof). CancellationException is rethrown.
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
            if (highWater.get() == Long.MIN_VALUE) highWater.set(query.latestCloseTimeMs() ?: 0L)
            val since = highWater.get()
            val closes = source.closedSince(since).sortedBy { it.closeTimeMs } // oldest-first
            if (closes.isEmpty()) return
            val now = clock.instant().toEpochMilli()
            var maxConfirmed = since
            var journaled = 0
            for (cp in closes) {
                val result = journal.recordClose(cp.toJournaled(now))
                if (result == CloseWriteResult.FAILED) {
                    // Do NOT advance past an unconfirmed close — keeps MAX(close_time) == confirmed prefix,
                    // so neither this run nor a restart reseed can gap it. Retried next cycle.
                    log.warn { "Stopping close capture at posId=${cp.posId} (write failed); will retry next cycle" }
                    break
                }
                // JOURNALED or DUPLICATE == confirmed persisted.
                if (cp.closeTimeMs > maxConfirmed) maxConfirmed = cp.closeTimeMs
                journaled++
            }
            highWater.set(maxConfirmed)
            if (journaled > 0) log.info { "Captured $journaled trade close(s); high-water=$maxConfirmed" }
        } catch (e: CancellationException) {
            throw e
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

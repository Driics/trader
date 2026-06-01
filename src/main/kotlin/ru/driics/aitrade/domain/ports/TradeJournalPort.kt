package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.journal.JournaledFill
import ru.driics.aitrade.domain.journal.JournaledOrder
import ru.driics.aitrade.domain.journal.JournaledPnlSnapshot

/** Outcome of a [TradeJournalPort.recordClose] write — lets the collector advance its high-water mark only over confirmed writes. */
enum class CloseWriteResult { JOURNALED, DUPLICATE, FAILED }

/**
 * Persists trade-journal rows (orders, fills, PnL snapshots) for audit/history.
 *
 * Implementations MUST be fail-safe — a journal write error must never break a trading cycle. A DB
 * outage is logged and swallowed, never rethrown. When the journal is disabled (the default) the
 * [ru.driics.aitrade.infra.persistence.NoOpTradeJournal] is wired so no datasource is required to boot.
 */
interface TradeJournalPort {
    fun recordOrder(order: JournaledOrder)
    fun recordFill(fill: JournaledFill)
    fun recordPnlSnapshot(snapshot: JournaledPnlSnapshot)
    fun recordClose(close: JournaledClose): CloseWriteResult
}

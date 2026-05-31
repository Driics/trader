package ru.driics.aitrade.infra.persistence

import ru.driics.aitrade.domain.journal.JournaledFill
import ru.driics.aitrade.domain.journal.JournaledOrder
import ru.driics.aitrade.domain.journal.JournaledPnlSnapshot
import ru.driics.aitrade.domain.ports.TradeJournalPort

/**
 * No-op trade journal used when `trade-journal.enabled` is not `true` (the default). Lets the app boot
 * with no datasource while [TradeJournalPort] still has a bean, so callers never need a null check.
 */
class NoOpTradeJournal : TradeJournalPort {
    override fun recordOrder(order: JournaledOrder) {}
    override fun recordFill(fill: JournaledFill) {}
    override fun recordPnlSnapshot(snapshot: JournaledPnlSnapshot) {}
}

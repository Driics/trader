package ru.driics.aitrade.infra.persistence

import ru.driics.aitrade.domain.ports.*

/** Used when the journal is disabled: every read is empty. */
class NoOpTradeJournalQuery : TradeJournalQueryPort {
    override fun latestCloseTimeMs(): Long? = null
    override fun closedTrades(filter: AnalyticsFilter): List<ClosedTradeRow> = emptyList()
    override fun entryRisks(filter: AnalyticsFilter): List<EntryRiskRow> = emptyList()
    override fun pnlSnapshots(filter: AnalyticsFilter): List<PnlSnapshotRow> = emptyList()
}

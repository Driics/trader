package ru.driics.aitrade.domain.ports

import java.math.BigDecimal

/** Filters shared by all analytics reads. Null = unbounded/any. */
data class AnalyticsFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val mode: String? = null,
    val symbol: String? = null,
)

data class ClosedTradeRow(
    val posId: String, val instId: String, val symbol: String, val side: String?,
    val realizedPnl: BigDecimal, val openTimeMs: Long, val closeTimeMs: Long,
)

/** A PLACED entry's intended risk, for linking closes -> risk (avg-R). */
data class EntryRiskRow(val instId: String, val recordedAtMs: Long, val riskUsd: BigDecimal?)

data class PnlSnapshotRow(val timestampMs: Long, val accountValue: BigDecimal)

/** Read side of the journal (separate from the write [TradeJournalPort]). */
interface TradeJournalQueryPort {
    fun latestCloseTimeMs(): Long?
    fun closedTrades(filter: AnalyticsFilter): List<ClosedTradeRow>
    fun entryRisks(filter: AnalyticsFilter): List<EntryRiskRow>
    fun pnlSnapshots(filter: AnalyticsFilter): List<PnlSnapshotRow>
}

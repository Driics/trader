package ru.driics.aitrade.infra.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.journal.JournaledFill
import ru.driics.aitrade.domain.journal.JournaledOrder
import ru.driics.aitrade.domain.journal.JournaledPnlSnapshot
import ru.driics.aitrade.domain.ports.TradeJournalPort
import java.sql.Timestamp
import java.time.Instant

/**
 * JDBC-backed trade journal over a [NamedParameterJdbcTemplate]. Each write is fail-safe — mirroring
 * [ru.driics.aitrade.infra.recording.JsonlDecisionLogSink]: any [Exception] from the INSERT is logged at
 * WARN and swallowed, never rethrown, so a DB outage can never break a trading cycle.
 *
 * Wired only when `trade-journal.enabled=true` (see
 * [ru.driics.aitrade.config.TradeJournalPersistenceConfig]); otherwise [NoOpTradeJournal] is used.
 */
class JdbcTradeJournal(
    private val jdbc: NamedParameterJdbcTemplate,
) : TradeJournalPort {

    private companion object {
        val log = logger<JdbcTradeJournal>()
    }

    override fun recordOrder(order: JournaledOrder) {
        try {
            val params = MapSqlParameterSource()
                .addValue("recorded_at", toTimestamp(order.timestampMs))
                .addValue("mode", order.mode.name)
                .addValue("symbol", order.symbol)
                .addValue("inst_id", order.instId)
                .addValue("side", order.side.value)
                .addValue("leverage", order.leverage)
                .addValue("requested_contracts", order.requestedContracts)
                .addValue("placed_contracts", order.placedContracts)
                .addValue("entry_px", order.entryPx)
                .addValue("tp_px", order.tpPx)
                .addValue("sl_px", order.slPx)
                .addValue("risk_usd", order.riskUsd)
                .addValue("cost_usd", order.costUsd)
                .addValue("cl_ord_id", order.clOrdId)
                .addValue("ord_id", order.ordId)
                .addValue("status", order.status)
                .addValue("reason", order.reason)
                .addValue("demo", order.demo)
            jdbc.update(
                """
                INSERT INTO trade_order
                    (recorded_at, mode, symbol, inst_id, side, leverage, requested_contracts,
                     placed_contracts, entry_px, tp_px, sl_px, risk_usd, cost_usd, cl_ord_id,
                     ord_id, status, reason, demo)
                VALUES
                    (:recorded_at, :mode, :symbol, :inst_id, :side, :leverage, :requested_contracts,
                     :placed_contracts, :entry_px, :tp_px, :sl_px, :risk_usd, :cost_usd, :cl_ord_id,
                     :ord_id, :status, :reason, :demo)
                """.trimIndent(),
                params,
            )
        } catch (e: Exception) {
            log.warn(e) { "Failed to journal order clOrdId=${order.clOrdId} (cycle continues)" }
        }
    }

    override fun recordFill(fill: JournaledFill) {
        try {
            val params = MapSqlParameterSource()
                .addValue("recorded_at", toTimestamp(fill.timestampMs))
                .addValue("ord_id", fill.ordId)
                .addValue("cl_ord_id", fill.clOrdId)
                .addValue("inst_id", fill.instId)
                .addValue("side", fill.side)
                .addValue("avg_px", fill.avgPx)
                .addValue("state", fill.state)
            jdbc.update(
                """
                INSERT INTO fill
                    (recorded_at, ord_id, cl_ord_id, inst_id, side, avg_px, state)
                VALUES
                    (:recorded_at, :ord_id, :cl_ord_id, :inst_id, :side, :avg_px, :state)
                """.trimIndent(),
                params,
            )
        } catch (e: Exception) {
            log.warn(e) { "Failed to journal fill ordId=${fill.ordId} (cycle continues)" }
        }
    }

    override fun recordPnlSnapshot(snapshot: JournaledPnlSnapshot) {
        try {
            val params = MapSqlParameterSource()
                .addValue("recorded_at", toTimestamp(snapshot.timestampMs))
                .addValue("cycle", snapshot.cycle)
                .addValue("account_value", snapshot.accountValue)
                .addValue("available_cash", snapshot.availableCash)
                .addValue("total_return", snapshot.totalReturn)
                .addValue("realized_pnl_today", snapshot.realizedPnlToday)
                .addValue("open_positions_count", snapshot.openPositionsCount)
            jdbc.update(
                """
                INSERT INTO pnl_snapshot
                    (recorded_at, cycle, account_value, available_cash, total_return,
                     realized_pnl_today, open_positions_count)
                VALUES
                    (:recorded_at, :cycle, :account_value, :available_cash, :total_return,
                     :realized_pnl_today, :open_positions_count)
                """.trimIndent(),
                params,
            )
        } catch (e: Exception) {
            log.warn(e) { "Failed to journal PnL snapshot cycle=${snapshot.cycle} (cycle continues)" }
        }
    }

    override fun recordClose(close: JournaledClose) {
        try {
            val params = MapSqlParameterSource()
                .addValue("recorded_at", toTimestamp(close.recordedAtMs))
                .addValue("pos_id", close.posId)
                .addValue("inst_id", close.instId)
                .addValue("symbol", close.symbol)
                .addValue("side", close.side)
                .addValue("realized_pnl", close.realizedPnl)
                .addValue("open_time", toTimestamp(close.openTimeMs))
                .addValue("close_time", toTimestamp(close.closeTimeMs))
                .addValue("mode", close.mode.name)
                .addValue("demo", close.demo)
            jdbc.update(
                """
                INSERT INTO trade_close
                    (recorded_at, pos_id, inst_id, symbol, side, realized_pnl, open_time, close_time, mode, demo)
                VALUES
                    (:recorded_at, :pos_id, :inst_id, :symbol, :side, :realized_pnl, :open_time, :close_time, :mode, :demo)
                """.trimIndent(),
                params,
            )
        } catch (e: Exception) {
            // Idempotent + fail-safe: a UNIQUE(pos_id) violation means "already journaled"; any other error
            // must never break a cycle. Both are logged and swallowed.
            log.warn(e) { "Skipped journaling close posId=${close.posId} (duplicate or DB error; cycle continues)" }
        }
    }

    private fun toTimestamp(timestampMs: Long): Timestamp = Timestamp.from(Instant.ofEpochMilli(timestampMs))
}

package ru.driics.aitrade.infra.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.driics.aitrade.domain.ports.*
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * JDBC reads over the journal tables. Time filters compare close_time/recorded_at against epoch-ms
 * bounds; mode/symbol are equality filters. Returns raw rows — metric math lives elsewhere.
 */
class JdbcTradeJournalQuery(
    private val jdbc: NamedParameterJdbcTemplate,
) : TradeJournalQueryPort {

    override fun latestCloseTimeMs(): Long? =
        jdbc.queryForObject(
            "SELECT MAX(close_time) FROM trade_close", MapSqlParameterSource(),
        ) { rs, _ -> rs.getTimestamp(1)?.time }

    override fun closedTrades(filter: AnalyticsFilter): List<ClosedTradeRow> {
        val (where, params) = whereFor(filter, timeCol = "close_time", symbolCol = "symbol", modeCol = "mode")
        return jdbc.query(
            "SELECT pos_id, inst_id, symbol, side, realized_pnl, open_time, close_time FROM trade_close $where ORDER BY close_time",
            params,
        ) { rs, _ ->
            ClosedTradeRow(
                posId = rs.getString("pos_id"), instId = rs.getString("inst_id"),
                symbol = rs.getString("symbol"), side = rs.getString("side"),
                realizedPnl = rs.getBigDecimal("realized_pnl") ?: BigDecimal.ZERO,
                openTimeMs = rs.ms("open_time"), closeTimeMs = rs.ms("close_time"),
            )
        }
    }

    override fun entryRisks(filter: AnalyticsFilter): List<EntryRiskRow> {
        val (where, params) = whereFor(filter, timeCol = "recorded_at", symbolCol = "symbol", modeCol = "mode")
        val clause = if (where.isEmpty()) "WHERE status = 'PLACED'" else "$where AND status = 'PLACED'"
        return jdbc.query(
            "SELECT inst_id, recorded_at, risk_usd FROM trade_order $clause ORDER BY recorded_at",
            params,
        ) { rs, _ -> EntryRiskRow(rs.getString("inst_id"), rs.ms("recorded_at"), rs.getBigDecimal("risk_usd")) }
    }

    override fun pnlSnapshots(filter: AnalyticsFilter): List<PnlSnapshotRow> {
        val (where, params) = whereFor(filter, timeCol = "recorded_at", symbolCol = null, modeCol = null)
        return jdbc.query(
            "SELECT recorded_at, account_value FROM pnl_snapshot $where ORDER BY recorded_at",
            params,
        ) { rs, _ -> PnlSnapshotRow(rs.ms("recorded_at"), rs.getBigDecimal("account_value") ?: BigDecimal.ZERO) }
    }

    private fun whereFor(
        f: AnalyticsFilter,
        timeCol: String,
        symbolCol: String?,
        modeCol: String?,
    ): Pair<String, MapSqlParameterSource> {
        val conds = ArrayList<String>()
        val p = MapSqlParameterSource()
        f.fromMs?.let { conds += "$timeCol >= :from"; p.addValue("from", Timestamp(it)) }
        f.toMs?.let { conds += "$timeCol <= :to"; p.addValue("to", Timestamp(it)) }
        if (symbolCol != null) f.symbol?.let { conds += "$symbolCol = :symbol"; p.addValue("symbol", it) }
        if (modeCol != null) f.mode?.let { conds += "$modeCol = :mode"; p.addValue("mode", it) }
        val where = if (conds.isEmpty()) "" else "WHERE " + conds.joinToString(" AND ")
        return where to p
    }

    private fun ResultSet.ms(col: String): Long = getTimestamp(col)?.time ?: 0L
}

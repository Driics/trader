package ru.driics.aitrade.infra.persistence

import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.driics.aitrade.domain.journal.JournaledFill
import ru.driics.aitrade.domain.journal.JournaledOrder
import ru.driics.aitrade.domain.journal.JournaledPnlSnapshot
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.types.OrderSide
import java.math.BigDecimal
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H2 integration test for [JdbcTradeJournal] — NO Spring context, NO Supabase. It runs the SAME Liquibase
 * changelog the production wiring uses, so the changeset is proven valid (it executes on H2 in PostgreSQL
 * mode), and verifies each record* method persists a readable row plus the fail-safe (no rethrow) contract.
 */
class JdbcTradeJournalTest {

    private lateinit var dataSource: org.h2.jdbcx.JdbcDataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var journal: JdbcTradeJournal

    private val dbName = "journal_${System.nanoTime()}"

    @BeforeEach
    fun setUp() {
        dataSource = org.h2.jdbcx.JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        runChangelog(dataSource)
        jdbc = NamedParameterJdbcTemplate(dataSource)
        journal = JdbcTradeJournal(jdbc)
    }

    @AfterEach
    fun tearDown() {
        dataSource.connection.use { it.createStatement().execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `recordOrder persists a readable row`() {
        val order = JournaledOrder(
            timestampMs = 1_700_000_000_000L,
            mode = TradingMode.PAPER,
            symbol = "BTC",
            instId = "BTC-USDT-SWAP",
            side = OrderSide.BUY,
            leverage = 5,
            requestedContracts = BigDecimal("1.5"),
            placedContracts = BigDecimal("1.5"),
            entryPx = BigDecimal("42000.123456789012345678"),
            tpPx = BigDecimal("43000"),
            slPx = BigDecimal("41000"),
            riskUsd = BigDecimal("100.50"),
            costUsd = BigDecimal("250.75"),
            clOrdId = "cl-order-1",
            ordId = "ord-1",
            status = "PLACED",
            reason = null,
            demo = true,
        )

        journal.recordOrder(order)

        val row = jdbc.queryForMap(
            "SELECT * FROM trade_order WHERE cl_ord_id = :cl",
            MapSqlParameterSource("cl", "cl-order-1"),
        )
        assertEquals("PAPER", row["mode"])
        assertEquals("BTC", row["symbol"])
        assertEquals("BTC-USDT-SWAP", row["inst_id"])
        assertEquals("buy", row["side"])
        assertEquals(5, (row["leverage"] as Number).toInt())
        assertEquals(0, BigDecimal("1.5").compareTo(row["requested_contracts"] as BigDecimal))
        assertEquals(0, BigDecimal("42000.123456789012345678").compareTo(row["entry_px"] as BigDecimal))
        assertEquals("ord-1", row["ord_id"])
        assertEquals("PLACED", row["status"])
        assertNull(row["reason"])
        assertEquals(true, row["demo"])
    }

    @Test
    fun `recordOrder persists a rejected order with null prices`() {
        val order = JournaledOrder(
            timestampMs = 1_700_000_000_000L,
            mode = TradingMode.SIMULATION,
            symbol = "ETH",
            instId = "ETH-USDT-SWAP",
            side = OrderSide.SELL,
            leverage = 3,
            requestedContracts = null,
            placedContracts = null,
            entryPx = null,
            tpPx = null,
            slPx = null,
            riskUsd = null,
            costUsd = null,
            clOrdId = "cl-rejected-1",
            ordId = null,
            status = "REJECTED",
            reason = "risk gate blocked",
            demo = false,
        )

        journal.recordOrder(order)

        val row = jdbc.queryForMap(
            "SELECT * FROM trade_order WHERE cl_ord_id = :cl",
            MapSqlParameterSource("cl", "cl-rejected-1"),
        )
        assertEquals("REJECTED", row["status"])
        assertEquals("risk gate blocked", row["reason"])
        assertEquals("sell", row["side"])
        assertNull(row["ord_id"])
        assertNull(row["entry_px"])
        assertEquals(false, row["demo"])
    }

    @Test
    fun `recordFill persists a readable row`() {
        val fill = JournaledFill(
            timestampMs = 1_700_000_001_000L,
            ordId = "ord-99",
            clOrdId = "cl-99",
            instId = "BTC-USDT-SWAP",
            side = "buy",
            avgPx = BigDecimal("42010.5"),
            state = "filled",
        )

        journal.recordFill(fill)

        val row = jdbc.queryForMap(
            "SELECT * FROM fill WHERE ord_id = :ord",
            MapSqlParameterSource("ord", "ord-99"),
        )
        assertEquals("cl-99", row["cl_ord_id"])
        assertEquals("BTC-USDT-SWAP", row["inst_id"])
        assertEquals("buy", row["side"])
        assertEquals(0, BigDecimal("42010.5").compareTo(row["avg_px"] as BigDecimal))
        assertEquals("filled", row["state"])
    }

    @Test
    fun `recordPnlSnapshot persists a readable row`() {
        val snapshot = JournaledPnlSnapshot(
            timestampMs = 1_700_000_002_000L,
            cycle = 42L,
            accountValue = BigDecimal("10000.00"),
            availableCash = BigDecimal("8000.00"),
            totalReturn = BigDecimal("0.05"),
            realizedPnlToday = BigDecimal("-12.34"),
            openPositionsCount = 2,
        )

        journal.recordPnlSnapshot(snapshot)

        val row = jdbc.queryForMap(
            "SELECT * FROM pnl_snapshot WHERE cycle = :cycle",
            MapSqlParameterSource("cycle", 42L),
        )
        assertEquals(0, BigDecimal("10000.00").compareTo(row["account_value"] as BigDecimal))
        assertEquals(0, BigDecimal("8000.00").compareTo(row["available_cash"] as BigDecimal))
        assertEquals(0, BigDecimal("0.05").compareTo(row["total_return"] as BigDecimal))
        assertEquals(0, BigDecimal("-12.34").compareTo(row["realized_pnl_today"] as BigDecimal))
        assertEquals(2, (row["open_positions_count"] as Number).toInt())
    }

    @Test
    fun `recordPnlSnapshot persists null realized pnl`() {
        val snapshot = JournaledPnlSnapshot(
            timestampMs = 1_700_000_003_000L,
            cycle = 43L,
            accountValue = BigDecimal("10000"),
            availableCash = BigDecimal("9000"),
            totalReturn = BigDecimal.ZERO,
            realizedPnlToday = null,
            openPositionsCount = 0,
        )

        journal.recordPnlSnapshot(snapshot)

        val row = jdbc.queryForMap(
            "SELECT * FROM pnl_snapshot WHERE cycle = :cycle",
            MapSqlParameterSource("cycle", 43L),
        )
        assertNull(row["realized_pnl_today"])
        assertEquals(0, (row["open_positions_count"] as Number).toInt())
    }

    @Test
    fun `record methods are fail-safe and never rethrow when the table is gone`() {
        // Drop every table so each INSERT fails — the journal must swallow the exception, not rethrow.
        dataSource.connection.use { it.createStatement().execute("DROP ALL OBJECTS") }

        val order = JournaledOrder(
            timestampMs = 1L, mode = TradingMode.LIVE, symbol = "BTC", instId = "BTC-USDT-SWAP",
            side = OrderSide.BUY, leverage = 1, requestedContracts = null, placedContracts = null,
            entryPx = null, tpPx = null, slPx = null, riskUsd = null, costUsd = null,
            clOrdId = "x", ordId = null, status = "PLACED", reason = null, demo = false,
        )
        val fill = JournaledFill(
            timestampMs = 1L, ordId = "x", clOrdId = null, instId = "BTC-USDT-SWAP",
            side = "buy", avgPx = null, state = "live",
        )
        val snapshot = JournaledPnlSnapshot(
            timestampMs = 1L, cycle = 1L, accountValue = BigDecimal.ONE, availableCash = BigDecimal.ONE,
            totalReturn = BigDecimal.ZERO, realizedPnlToday = null, openPositionsCount = 0,
        )

        // None of these may throw.
        journal.recordOrder(order)
        journal.recordFill(fill)
        journal.recordPnlSnapshot(snapshot)
        assertTrue(true, "record* swallowed the DB error without rethrowing")
    }

    private fun runChangelog(ds: DataSource) {
        ds.connection.use { connection ->
            val database = liquibase.database.DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(JdbcConnection(connection))
            Liquibase(
                "db/changelog/db.changelog-master.yaml",
                ClassLoaderResourceAccessor(),
                database,
            ).use { liquibase ->
                liquibase.update("")
            }
        }
    }
}

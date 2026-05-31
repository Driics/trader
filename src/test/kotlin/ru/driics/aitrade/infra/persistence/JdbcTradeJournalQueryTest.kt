package ru.driics.aitrade.infra.persistence

import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.journal.JournaledPnlSnapshot
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.AnalyticsFilter
import java.math.BigDecimal
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JdbcTradeJournalQueryTest {

    private lateinit var dataSource: org.h2.jdbcx.JdbcDataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var journal: JdbcTradeJournal
    private lateinit var query: JdbcTradeJournalQuery

    private val dbName = "journal_query_${System.nanoTime()}"

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
        query = JdbcTradeJournalQuery(jdbc)
    }

    @AfterEach
    fun tearDown() {
        dataSource.connection.use { it.createStatement().execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `closedTrades returns inserted closes filtered by symbol, and latestCloseTimeMs is the max`() {
        journal.recordClose(JournaledClose(1L, "p1", "BTC-USDT-SWAP", "BTC", "long",
            BigDecimal("10"), 1L, 1_000L, TradingMode.PAPER, true))
        journal.recordClose(JournaledClose(2L, "p2", "ETH-USDT-SWAP", "ETH", "short",
            BigDecimal("-4"), 1L, 2_000L, TradingMode.PAPER, true))

        val all = query.closedTrades(AnalyticsFilter())
        assertEquals(2, all.size)
        val btc = query.closedTrades(AnalyticsFilter(symbol = "BTC"))
        assertEquals(1, btc.size)
        assertEquals("p1", btc[0].posId)
        assertEquals(2_000L, query.latestCloseTimeMs())
    }

    @Test
    fun `latestCloseTimeMs returns null when no closes exist`() {
        assertNull(query.latestCloseTimeMs())
    }

    @Test
    fun `pnlSnapshots returns the equity series ordered by time`() {
        journal.recordPnlSnapshot(JournaledPnlSnapshot(100L, 1L, BigDecimal("10000"), BigDecimal("9000"),
            BigDecimal.ZERO, null, 0))
        journal.recordPnlSnapshot(JournaledPnlSnapshot(200L, 2L, BigDecimal("10100"), BigDecimal("9000"),
            BigDecimal.ZERO, null, 1))
        val series = query.pnlSnapshots(AnalyticsFilter())
        assertEquals(listOf(100L, 200L), series.map { it.timestampMs })
        assertEquals(0, BigDecimal("10100").compareTo(series[1].accountValue))
    }

    @Test
    fun `closedTrades returns empty list when no rows`() {
        val result = query.closedTrades(AnalyticsFilter())
        assertEquals(0, result.size)
    }

    @Test
    fun `pnlSnapshots returns empty list when no rows`() {
        val result = query.pnlSnapshots(AnalyticsFilter())
        assertEquals(0, result.size)
    }

    @Test
    fun `entryRisks returns empty list when no rows`() {
        val result = query.entryRisks(AnalyticsFilter())
        assertEquals(0, result.size)
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

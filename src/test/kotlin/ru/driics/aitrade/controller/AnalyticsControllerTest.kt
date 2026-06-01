package ru.driics.aitrade.controller

import io.mockk.*
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.driics.aitrade.domain.analytics.PerformanceAnalytics
import ru.driics.aitrade.domain.ports.*
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsControllerTest {
    private val query = mockk<TradeJournalQueryPort>(relaxed = true)
    private fun mvc(enabled: Boolean): MockMvc =
        MockMvcBuilders.standaloneSetup(AnalyticsController(query, PerformanceAnalytics(), enabled)).build()

    @Test fun `summary returns enabled=true and metrics`() {
        every { query.closedTrades(any()) } returns listOf(
            ClosedTradeRow("p1","BTC-USDT-SWAP","BTC","long", BigDecimal("10"), 0, 1))
        every { query.entryRisks(any()) } returns emptyList()
        every { query.pnlSnapshots(any()) } returns emptyList()

        mvc(enabled = true).perform(get("/api/analytics/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.data.totalTrades").value(1))
            .andExpect(jsonPath("$.data.wins").value(1))
    }

    @Test fun `summary returns enabled=false envelope when journal disabled`() {
        mvc(enabled = false).perform(get("/api/analytics/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.data.totalTrades").value(0))
    }

    @Test fun `by-symbol returns enabled envelope and grouped data`() {
        every { query.closedTrades(any()) } returns listOf(
            ClosedTradeRow("p1", "BTC-USDT-SWAP", "BTC", "long", BigDecimal("10"), 0, 1),
            ClosedTradeRow("p2", "ETH-USDT-SWAP", "ETH", "long", BigDecimal("5"), 0, 2),
        )
        every { query.entryRisks(any()) } returns emptyList()
        every { query.pnlSnapshots(any()) } returns emptyList()

        mvc(enabled = true).perform(get("/api/analytics/by-symbol"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.data.length()").value(2))
    }

    @Test fun `equity-curve returns points with drawdownPct field`() {
        every { query.pnlSnapshots(any()) } returns listOf(
            PnlSnapshotRow(1, BigDecimal("100")),
            PnlSnapshotRow(2, BigDecimal("80")),
        )

        mvc(enabled = true).perform(get("/api/analytics/equity-curve"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].drawdownPct").exists())
    }

    @Test fun `trades returns per-trade R`() {
        every { query.closedTrades(any()) } returns listOf(
            ClosedTradeRow("p1", "BTC-USDT-SWAP", "BTC", "long", BigDecimal("20"), 0, 5),
        )
        every { query.entryRisks(any()) } returns listOf(
            EntryRiskRow("BTC-USDT-SWAP", 1, BigDecimal("10")),
        )

        mvc(enabled = true).perform(get("/api/analytics/trades"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].r").value(2.0))
    }

    @Test fun `entry risks are fetched WITHOUT the time filter (I3 regression)`() {
        val closesSlot = slot<AnalyticsFilter>()
        val entriesSlot = slot<AnalyticsFilter>()

        every { query.closedTrades(capture(closesSlot)) } returns emptyList()
        every { query.entryRisks(capture(entriesSlot)) } returns emptyList()
        every { query.pnlSnapshots(any()) } returns emptyList()

        mvc(enabled = true).perform(
            get("/api/analytics/summary")
                .param("from", "100")
                .param("to", "200")
                .param("symbol", "BTC")
                .param("mode", "PAPER")
        ).andExpect(status().isOk)

        // closes ARE time-bounded
        assertEquals(100L, closesSlot.captured.fromMs)
        assertEquals(200L, closesSlot.captured.toMs)

        // entries are NOT time-bounded (I3 fix)
        assertNull(entriesSlot.captured.fromMs)
        assertNull(entriesSlot.captured.toMs)
        // but other filters are preserved
        assertEquals("BTC", entriesSlot.captured.symbol)
        assertEquals("PAPER", entriesSlot.captured.mode)
    }
}

package ru.driics.aitrade.controller

import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.driics.aitrade.domain.analytics.PerformanceAnalytics
import ru.driics.aitrade.domain.ports.*
import java.math.BigDecimal
import kotlin.test.Test

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
}

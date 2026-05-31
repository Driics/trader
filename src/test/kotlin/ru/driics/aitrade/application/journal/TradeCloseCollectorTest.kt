package ru.driics.aitrade.application.journal

import io.mockk.*
import kotlinx.coroutines.runBlocking
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeCloseCollectorTest {
    private val source = mockk<ClosedPositionsPort>()
    private val journal = mockk<TradeJournalPort>(relaxed = true)
    private val query = mockk<TradeJournalQueryPort>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(9_000L), ZoneOffset.UTC)
    private fun collector() = TradeCloseCollector(source, journal, query, clock, TradingMode.PAPER, demo = true)

    @Test
    fun `journals new closes and advances the high-water mark`() = runBlocking {
        every { query.latestCloseTimeMs() } returns 1_000L
        coEvery { source.closedSince(1_000L) } returns listOf(
            ClosedPosition("p2", "BTC-USDT-SWAP", "long", BigDecimal("10"), 1L, 2_000L))
        val c = collector()

        c.collect()

        val slot = slot<JournaledClose>()
        verify(exactly = 1) { journal.recordClose(capture(slot)) }
        assertEquals("p2", slot.captured.posId)
        assertEquals(2_000L, slot.captured.closeTimeMs)
        assertEquals("BTC", slot.captured.symbol)

        coEvery { source.closedSince(2_000L) } returns emptyList()
        c.collect()
        coVerify { source.closedSince(2_000L) }
    }

    @Test
    fun `never throws when the source blows up`() = runBlocking {
        every { query.latestCloseTimeMs() } returns null
        coEvery { source.closedSince(any()) } throws RuntimeException("boom")
        collector().collect() // must not throw
    }
}

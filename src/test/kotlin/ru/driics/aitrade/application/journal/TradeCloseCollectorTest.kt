package ru.driics.aitrade.application.journal

import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.CloseWriteResult
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradeCloseCollectorTest {
    private val source = mockk<ClosedPositionsPort>()
    private val journal = mockk<TradeJournalPort>(relaxed = true)
    private val query = mockk<TradeJournalQueryPort>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(9_000L), ZoneOffset.UTC)
    private fun collector() = TradeCloseCollector(source, journal, query, clock, TradingMode.PAPER, demo = true)

    @Test
    fun `journals new closes and advances the high-water mark`() = runBlocking {
        every { query.latestCloseTimeMs() } returns 1_000L
        every { journal.recordClose(any()) } returns CloseWriteResult.JOURNALED
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

    @Test
    fun `does not advance the mark past a close whose write FAILED (gap-proof)`() = runBlocking {
        // Both closes fetched; oldest (A=100) fails, so mark must stay at 0 and B=200 is never confirmed.
        every { query.latestCloseTimeMs() } returns 0L
        val closeA = ClosedPosition("posA", "BTC-USDT-SWAP", "long", BigDecimal("1"), 1L, 100L)
        val closeB = ClosedPosition("posB", "BTC-USDT-SWAP", "long", BigDecimal("2"), 1L, 200L)
        coEvery { source.closedSince(0L) } returns listOf(closeA, closeB)

        // Default: JOURNALED; override for A (closeTimeMs=100) to FAILED.
        every { journal.recordClose(any()) } returns CloseWriteResult.JOURNALED
        every { journal.recordClose(match { it.closeTimeMs == 100L }) } returns CloseWriteResult.FAILED

        val c = collector()
        c.collect()

        // Mark stayed at 0 because the oldest (A) failed first and we broke.
        // Second collect must call closedSince(0) again, not closedSince(200).
        coEvery { source.closedSince(0L) } returns emptyList()
        c.collect()
        coVerify(exactly = 2) { source.closedSince(0L) }
        coVerify(exactly = 0) { source.closedSince(200L) }
    }

    @Test
    fun `advances only to the confirmed prefix when a later close fails`() = runBlocking {
        // A=100 succeeds, B=200 fails → mark advances to 100, not 200.
        every { query.latestCloseTimeMs() } returns 0L
        val closeA = ClosedPosition("posA", "BTC-USDT-SWAP", "long", BigDecimal("1"), 1L, 100L)
        val closeB = ClosedPosition("posB", "BTC-USDT-SWAP", "long", BigDecimal("2"), 1L, 200L)
        coEvery { source.closedSince(0L) } returns listOf(closeA, closeB)

        every { journal.recordClose(any()) } returns CloseWriteResult.JOURNALED
        every { journal.recordClose(match { it.closeTimeMs == 200L }) } returns CloseWriteResult.FAILED

        val c = collector()
        c.collect()

        // Mark should be at 100 (A confirmed), so next cycle fetches since=100.
        coEvery { source.closedSince(100L) } returns emptyList()
        c.collect()
        coVerify(exactly = 1) { source.closedSince(100L) }
        coVerify(exactly = 0) { source.closedSince(200L) }
    }

    @Test
    fun `rethrows CancellationException`() = runBlocking {
        every { query.latestCloseTimeMs() } returns 0L
        coEvery { source.closedSince(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            collector().collect()
        }
        Unit
    }
}

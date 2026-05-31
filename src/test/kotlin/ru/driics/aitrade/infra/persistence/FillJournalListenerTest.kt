package ru.driics.aitrade.infra.persistence

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.journal.JournaledFill
import ru.driics.aitrade.domain.ports.OrderEvent
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit test for [FillJournalListener]'s OrderEvent -> JournaledFill mapping. Tests the mapping
 * deterministically via the internal [FillJournalListener.recordFill] fun rather than racing the
 * @PostConstruct-launched collector.
 */
class FillJournalListenerTest {

    private val streaming = mockk<StreamingMarketDataPort>(relaxed = true)
    private val tradeJournal = mockk<TradeJournalPort>(relaxed = true)

    private val listener = FillJournalListener(streaming, tradeJournal)

    @Test
    fun `maps an OrderEvent to a JournaledFill and records it`() {
        val event = OrderEvent(
            instId = "BTC-USDT-SWAP",
            orderId = "OID-1",
            clOrdId = "CLI-1",
            state = "filled",
            side = "buy",
            avgPx = BigDecimal("50123.4"),
            timestamp = Instant.parse("2026-05-29T12:00:00Z"),
        )

        val captured = slot<JournaledFill>()
        listener.recordFill(event)

        verify(exactly = 1) { tradeJournal.recordFill(capture(captured)) }
        with(captured.captured) {
            assertEquals(event.timestamp.toEpochMilli(), timestampMs)
            assertEquals("OID-1", ordId)
            assertEquals("CLI-1", clOrdId)
            assertEquals("BTC-USDT-SWAP", instId)
            assertEquals("buy", side)
            assertEquals(BigDecimal("50123.4"), avgPx)
            assertEquals("filled", state)
        }
    }

    @Test
    fun `tolerates a null clOrdId and avgPx`() {
        val event = OrderEvent(
            instId = "ETH-USDT-SWAP",
            orderId = "OID-2",
            clOrdId = null,
            state = "live",
            side = "sell",
            avgPx = null,
            timestamp = Instant.parse("2026-05-29T12:00:01Z"),
        )

        val captured = slot<JournaledFill>()
        listener.recordFill(event)

        verify(exactly = 1) { tradeJournal.recordFill(capture(captured)) }
        with(captured.captured) {
            assertEquals(null, clOrdId)
            assertEquals(null, avgPx)
            assertEquals("OID-2", ordId)
        }
    }
}

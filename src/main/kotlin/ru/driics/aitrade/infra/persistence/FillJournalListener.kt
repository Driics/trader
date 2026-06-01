package ru.driics.aitrade.infra.persistence

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import ru.driics.aitrade.domain.journal.JournaledFill
import ru.driics.aitrade.domain.ports.OrderEvent
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.domain.ports.TradeJournalPort

/**
 * Bridges live OKX order-update events into the trade journal. Subscribes to
 * [StreamingMarketDataPort.observeOrderUpdates] and records each [OrderEvent] as a [JournaledFill].
 *
 * The journal port is always a bean (NoOp when disabled), so this listener is unconditional — no
 * conditional logic or try/catch here; the [TradeJournalPort] impl is itself fail-safe.
 */
@Component
class FillJournalListener(
    private val streaming: StreamingMarketDataPort,
    private val tradeJournal: TradeJournalPort,
) {
    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostConstruct
    fun start() {
        scope.launch {
            log.info { "FillJournalListener subscribing to order updates" }
            streaming.observeOrderUpdates().collect { event ->
                recordFill(event)
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    /**
     * Maps one [OrderEvent] to a [JournaledFill] and records it. Extracted so the mapping can be
     * tested deterministically without racing the launched collector.
     */
    internal fun recordFill(event: OrderEvent) {
        tradeJournal.recordFill(
            JournaledFill(
                timestampMs = event.timestamp.toEpochMilli(),
                ordId = event.orderId,
                clOrdId = event.clOrdId,
                instId = event.instId,
                side = event.side,
                avgPx = event.avgPx,
                state = event.state,
            )
        )
    }
}

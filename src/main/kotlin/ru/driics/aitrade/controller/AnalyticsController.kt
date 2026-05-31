package ru.driics.aitrade.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.driics.aitrade.domain.analytics.EquityPoint
import ru.driics.aitrade.domain.analytics.PerformanceAnalytics
import ru.driics.aitrade.domain.analytics.PerformanceSummary
import ru.driics.aitrade.domain.analytics.SymbolPerformance
import ru.driics.aitrade.domain.ports.AnalyticsFilter
import ru.driics.aitrade.domain.ports.ClosedTradeRow
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort

/** Read-only realized-performance API over the trade journal. Empty enabled:false envelope when off. */
@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(
    private val query: TradeJournalQueryPort,
    private val analytics: PerformanceAnalytics,
    @Value("\${trade-journal.enabled:false}") private val journalEnabled: Boolean,
) {
    data class Envelope<T>(val enabled: Boolean, val data: T)

    private fun filter(from: Long?, to: Long?, mode: String?, symbol: String?) =
        AnalyticsFilter(fromMs = from, toMs = to, mode = mode, symbol = symbol)

    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) mode: String?, @RequestParam(required = false) symbol: String?,
    ): Envelope<PerformanceSummary> {
        val f = filter(from, to, mode, symbol)
        return Envelope(journalEnabled, analytics.summary(query.closedTrades(f), query.entryRisks(f), query.pnlSnapshots(f)))
    }

    @GetMapping("/by-symbol")
    fun bySymbol(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) mode: String?,
    ): Envelope<List<SymbolPerformance>> {
        val f = filter(from, to, mode, null)
        return Envelope(journalEnabled, analytics.bySymbol(query.closedTrades(f), query.entryRisks(f), query.pnlSnapshots(f)))
    }

    @GetMapping("/equity-curve")
    fun equityCurve(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) mode: String?,
    ): Envelope<List<EquityPoint>> {
        val f = filter(from, to, mode, null)
        return Envelope(journalEnabled, analytics.equityCurve(query.pnlSnapshots(f)))
    }

    @GetMapping("/trades")
    fun trades(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) mode: String?, @RequestParam(required = false) symbol: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
    ): Envelope<List<ClosedTradeRow>> {
        val all = query.closedTrades(filter(from, to, mode, symbol)).sortedByDescending { it.closeTimeMs }
        return Envelope(journalEnabled, all.drop(offset).take(limit))
    }
}

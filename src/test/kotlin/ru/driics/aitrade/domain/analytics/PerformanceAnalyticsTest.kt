package ru.driics.aitrade.domain.analytics

import ru.driics.aitrade.domain.ports.ClosedTradeRow
import ru.driics.aitrade.domain.ports.EntryRiskRow
import ru.driics.aitrade.domain.ports.PnlSnapshotRow
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceAnalyticsTest {
    private val svc = PerformanceAnalytics()
    private fun close(sym: String, pnl: String, close: Long, inst: String = "$sym-USDT-SWAP", open: Long = 0L) =
        ClosedTradeRow("pos-$sym-$close", inst, sym, "long", BigDecimal(pnl), open, close)

    @Test fun `empty input yields a zeroed summary`() {
        val s = svc.summary(emptyList(), emptyList(), emptyList())
        assertEquals(0, s.totalTrades)
        assertEquals(0, BigDecimal.ZERO.compareTo(s.winRate))
        assertNull(s.profitFactor); assertNull(s.expectancyUsd); assertNull(s.avgR)
    }

    @Test fun `win-rate, profit factor, expectancy over wins and losses`() {
        val closes = listOf(close("BTC","30",3), close("BTC","-10",2), close("ETH","-20",1), close("ETH","0",4))
        val s = svc.summary(closes, emptyList(), emptyList())
        assertEquals(4, s.totalTrades); assertEquals(1, s.wins); assertEquals(2, s.losses); assertEquals(1, s.scratches)
        assertEquals(0, BigDecimal("0.2500").compareTo(s.winRate))          // 1 win / 4
        assertEquals(0, BigDecimal("30").compareTo(s.grossProfit))
        assertEquals(0, BigDecimal("30").compareTo(s.grossLoss))
        assertEquals(0, BigDecimal("1").compareTo(s.profitFactor!!))         // 30 / 30
        assertEquals(0, BigDecimal("0").compareTo(s.netRealizedPnl))         // 30-10-20+0
    }

    @Test fun `profit factor is null when there are no losses`() {
        val s = svc.summary(listOf(close("BTC","5",1), close("BTC","7",2)), emptyList(), emptyList())
        assertNull(s.profitFactor)
        assertEquals(0, BigDecimal("1.0000").compareTo(s.winRate))
    }

    @Test fun `avg-R links closes to the latest prior PLACED entry risk, missing risk excluded`() {
        val closes = listOf(close("BTC","20",2), close("ETH","-5",2))
        val entries = listOf(
            EntryRiskRow("BTC-USDT-SWAP", 1, BigDecimal("10")),  // BTC risk 10 -> R=+2
            EntryRiskRow("ETH-USDT-SWAP", 1, null),              // ETH risk missing -> excluded
        )
        val s = svc.summary(closes, entries, emptyList())
        assertEquals(1, s.rUnavailable)
        assertEquals(0, BigDecimal("2.0000").compareTo(s.avgR!!))           // only BTC: 20/10
    }

    @Test fun `max drawdown from the equity series in usd and pct`() {
        val series = listOf(
            PnlSnapshotRow(1, BigDecimal("100")), PnlSnapshotRow(2, BigDecimal("120")),
            PnlSnapshotRow(3, BigDecimal("90")),  PnlSnapshotRow(4, BigDecimal("110")),
        )
        val s = svc.summary(emptyList(), emptyList(), series)
        assertEquals(0, BigDecimal("30").compareTo(s.maxDrawdownUsd))       // 120 -> 90
        assertEquals(0, BigDecimal("0.2500").compareTo(s.maxDrawdownPct))   // 30/120
    }

    @Test fun `equityCurve carries running drawdown pct`() {
        val pts = svc.equityCurve(listOf(PnlSnapshotRow(1, BigDecimal("100")), PnlSnapshotRow(2, BigDecimal("80"))))
        assertEquals(2, pts.size)
        assertEquals(0, BigDecimal("0.0000").compareTo(pts[0].drawdownPct))
        assertEquals(0, BigDecimal("0.2000").compareTo(pts[1].drawdownPct)) // (100-80)/100
    }

    @Test fun `bySymbol groups independently`() {
        val closes = listOf(close("BTC","10",1), close("ETH","-3",2), close("BTC","-4",3))
        val out = svc.bySymbol(closes, emptyList(), emptyList()).associateBy { it.symbol }
        assertEquals(2, out["BTC"]!!.summary.totalTrades)
        assertEquals(1, out["ETH"]!!.summary.totalTrades)
        assertTrue(out["ETH"]!!.summary.losses == 1)
    }

    @Test fun `closedTradesWithR computes per-trade R and null when risk missing`() {
        val closes = listOf(close("BTC", "20", 5), close("ETH", "-5", 5))
        val entries = listOf(
            EntryRiskRow("BTC-USDT-SWAP", 1, BigDecimal("10")),  // BTC risk 10 -> R=2.0000
            EntryRiskRow("ETH-USDT-SWAP", 1, null),              // ETH risk missing -> null
        )
        val result = svc.closedTradesWithR(closes, entries).associateBy { it.symbol }
        assertEquals(0, BigDecimal("2.0000").compareTo(result["BTC"]!!.r!!))
        assertNull(result["ETH"]!!.r)
    }

    @Test fun `max drawdown reports the single worst event by pct (usd from same trough)`() {
        // series [100, 50, 1000, 900]
        // trough @50:  ddPct = 50/100 = 0.5000, ddUsd = 50
        // trough @900: ddPct = 100/1000 = 0.1000, ddUsd = 100
        // worst by pct is 0.5000 -> usd must be 50 (NOT 100)
        val series = listOf(
            PnlSnapshotRow(1, BigDecimal("100")),
            PnlSnapshotRow(2, BigDecimal("50")),
            PnlSnapshotRow(3, BigDecimal("1000")),
            PnlSnapshotRow(4, BigDecimal("900")),
        )
        val s = svc.summary(emptyList(), emptyList(), series)
        assertEquals(0, BigDecimal("0.5000").compareTo(s.maxDrawdownPct))
        assertEquals(0, BigDecimal("50").compareTo(s.maxDrawdownUsd))
    }
}

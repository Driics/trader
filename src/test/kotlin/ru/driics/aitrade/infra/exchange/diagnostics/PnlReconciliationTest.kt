package ru.driics.aitrade.infra.exchange.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.OkxBillData
import ru.driics.aitrade.domain.model.OkxPositionHistoryData
import java.math.BigDecimal
import java.time.Instant

/**
 * Deterministic spec for the offline reconciliation machinery (B0 verification harness).
 *
 * These synthetic fixtures prove the breakdown/delta logic without any live OKX data, so the harness
 * itself is trustworthy before the operator feeds it captured payloads. The "fee gap" case encodes the
 * most likely real discrepancy: OKX's positions-history rolls fees into `realizedPnl` while the cap's
 * bills `pnl` sum excludes them.
 */
class PnlReconciliationTest {

    private val dayStartMs = Instant.parse("2026-05-29T00:00:00Z").toEpochMilli()

    private fun bill(tsMs: Long, pnl: String, fee: String = "0", type: String = "2", subType: String = "1", ccy: String = "USDT") =
        OkxBillData(billId = "b$tsMs", timestamp = tsMs.toString(), pnl = pnl, fee = fee, currency = ccy, type = type, subType = subType)

    private fun pos(uTimeMs: Long, realizedPnl: String, pnl: String, fee: String = "0", fundingFee: String = "0", liqPenalty: String = "0", ccy: String = "USDT") =
        OkxPositionHistoryData(
            posId = "p$uTimeMs", updatedTime = uTimeMs.toString(), realizedPnl = realizedPnl,
            pnl = pnl, fee = fee, fundingFee = fundingFee, liqPenalty = liqPenalty, currency = ccy,
        )

    @Test
    fun `breakdownBills sums only today's pnl and counts exclusions`() {
        val bills = listOf(
            bill(dayStartMs + 3_600_000, "-20.5"),
            bill(dayStartMs + 60_000, "5.0"),
            bill(dayStartMs - 1, "-1000"),           // before midnight -> excluded
        )

        val b = PnlReconciliation.breakdownBills(bills, dayStartMs)

        assertEquals(2, b.includedCount)
        assertEquals(1, b.excludedCount)
        // -20.5 + 5.0 = -15.5 ; matches getTodaysRealizedPnlUsd semantics exactly
        assertEquals(0, BigDecimal("-15.5").compareTo(b.totalPnl))
    }

    @Test
    fun `breakdownBills groups by type subType and ccy`() {
        val bills = listOf(
            bill(dayStartMs + 1, "-1.0", type = "2", subType = "1", ccy = "USDT"),
            bill(dayStartMs + 2, "-2.0", type = "2", subType = "1", ccy = "USDT"),
            bill(dayStartMs + 3, "-0.5", fee = "-0.1", type = "8", subType = "173", ccy = "USDT"), // funding fee bill
        )

        val b = PnlReconciliation.breakdownBills(bills, dayStartMs)

        assertEquals(2, b.groups.size)
        val trade = b.groups.first { it.type == "2" }
        assertEquals(2, trade.count)
        assertEquals(0, BigDecimal("-3.0").compareTo(trade.pnlSum))
        val funding = b.groups.first { it.type == "8" }
        assertEquals(1, funding.count)
        assertEquals(0, BigDecimal("-0.1").compareTo(funding.feeSum))
    }

    @Test
    fun `breakdownPositionsHistory sums realizedPnl for positions closed today, split by ccy`() {
        val positions = listOf(
            pos(dayStartMs + 10, realizedPnl = "-12.0", pnl = "-10.0", fee = "-2.0"),
            pos(dayStartMs + 20, realizedPnl = "3.0", pnl = "3.5", fee = "-0.5", ccy = "USDC"),
            pos(dayStartMs - 5, realizedPnl = "-999", pnl = "-999"),  // closed before midnight -> excluded
        )

        val p = PnlReconciliation.breakdownPositionsHistory(positions, dayStartMs)

        assertEquals(2, p.includedCount)
        assertEquals(0, BigDecimal("-9.0").compareTo(p.realizedPnlSum)) // -12 + 3
        assertEquals(0, BigDecimal("-12.0").compareTo(p.byCcy.getValue("USDT")))
        assertEquals(0, BigDecimal("3.0").compareTo(p.byCcy.getValue("USDC")))
    }

    @Test
    fun `reconcile reports zero delta when bills pnl already matches the oracle`() {
        val bills = listOf(bill(dayStartMs + 1, "-32.0"))
        val positions = listOf(pos(dayStartMs + 1, realizedPnl = "-32.0", pnl = "-32.0"))

        val report = PnlReconciliation.reconcile(bills, positions, dayStartMs)

        assertEquals(0, BigDecimal.ZERO.compareTo(report.billsVsOracleDelta))
        assertTrue(report.render().contains("matches the oracle"))
    }

    @Test
    fun `reconcile attributes the gap to fees when oracle folds fees into realizedPnl`() {
        // Cap sees pnl only: -30. OKX realizedPnl = pnl(-30) + fee(-2) = -32.
        val bills = listOf(
            bill(dayStartMs + 1, "-18.0", fee = "-1.2"),
            bill(dayStartMs + 2, "-12.0", fee = "-0.8"),
        )
        val positions = listOf(pos(dayStartMs + 1, realizedPnl = "-32.0", pnl = "-30.0", fee = "-2.0"))

        val report = PnlReconciliation.reconcile(bills, positions, dayStartMs)

        assertEquals(0, BigDecimal("-30.0").compareTo(report.bills.totalPnl))
        assertEquals(0, BigDecimal("-2.0").compareTo(report.bills.totalFee))
        // bills.pnl - oracle = -30 - (-32) = +2 (the cap under-reports the loss by the fee total)
        assertEquals(0, BigDecimal("2.0").compareTo(report.billsVsOracleDelta))
        // bills.(pnl+fee) - oracle = -32 - (-32) = 0  -> fees fully explain the gap
        assertEquals(0, BigDecimal.ZERO.compareTo(report.billsPlusFeeVsOracleDelta))
        assertTrue(report.render().contains("GAP = fees"), "render must name the fee gap as the diagnosis")
    }

    @Test
    fun `oracle identity residual is zero when components sum to realizedPnl`() {
        val positions = listOf(
            pos(dayStartMs + 1, realizedPnl = "-9.7", pnl = "-8.0", fee = "-1.0", fundingFee = "-0.5", liqPenalty = "-0.2"),
        )

        val report = PnlReconciliation.reconcile(emptyList(), positions, dayStartMs)

        // -8.0 + -1.0 + -0.5 + -0.2 = -9.7  -> residual ~ 0
        assertEquals(0, BigDecimal.ZERO.compareTo(report.oracleIdentityResidual))
    }

    @Test
    fun `ui figure delta is surfaced when supplied`() {
        val bills = listOf(bill(dayStartMs + 1, "-15.0"))
        val report = PnlReconciliation.reconcile(bills, emptyList(), dayStartMs, uiFigureUsd = BigDecimal("-15.0"))

        assertEquals(0, BigDecimal.ZERO.compareTo(report.uiVsBillsDelta!!))
        assertTrue(report.render().contains("OKX UI figure"))
    }
}

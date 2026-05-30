package ru.driics.aitrade.infra.exchange.diagnostics

import ru.driics.aitrade.domain.model.OkxBillData
import ru.driics.aitrade.domain.model.OkxPositionHistoryData
import java.math.BigDecimal
import java.time.Instant

/**
 * Pure, offline reconciliation of the B0 daily realized-PnL accounting.
 *
 * The daily-loss cap trusts [ru.driics.aitrade.infra.exchange.OkxExchangeAdapter.getTodaysRealizedPnlUsd],
 * which sums the `pnl` field of `/account/bills` over today's bills. That interpretation is UNVERIFIED
 * against live OKX. This class lets an operator reconcile that sum against an INDEPENDENT oracle —
 * `/account/positions-history` `realizedPnl` (= pnl + fee + fundingFee + liqPenalty per OKX) — using
 * captured real payloads, with no network and no live funds.
 *
 * The PRIMARY output is the per-`type`/`subType`/`ccy` breakdown ([BillsBreakdown.groups]): it reveals
 * whether funding/fee bills or non-USD settlement currencies are polluting the cap's sum, which is the
 * decision input for filtering [OkxExchangeAdapter.realizedPnlContribution].
 *
 * [breakdownBills] deliberately replicates the adapter's summation so the reconciled number is exactly
 * what the cap uses, not an approximation.
 */
object PnlReconciliation {

    private fun String.toBd(): BigDecimal = toBigDecimalOrNull() ?: BigDecimal.ZERO
    private fun String.toMsOrMin(): Long = toLongOrNull() ?: Long.MIN_VALUE

    /** Sum of one (type, subType, ccy) cohort of today's bills. */
    data class BillGroup(
        val type: String,
        val subType: String,
        val ccy: String,
        val count: Int,
        val pnlSum: BigDecimal,
        val feeSum: BigDecimal,
    )

    /**
     * Bills accounting for the day starting at [dayStartMs].
     * [totalPnl] is byte-for-byte what `getTodaysRealizedPnlUsd` returns for the same bills:
     * the sum of `pnl` over bills with `ts >= dayStartMs`. [totalFee] is diagnostic only (the cap
     * does NOT currently include fees) — surfaced so reconciliation can tell whether fees explain a gap.
     */
    data class BillsBreakdown(
        val dayStartMs: Long,
        val includedCount: Int,
        val excludedCount: Int,
        val totalPnl: BigDecimal,
        val totalFee: BigDecimal,
        val groups: List<BillGroup>,
    )

    /**
     * Positions-history accounting for the day starting at [dayStartMs].
     * [realizedPnlSum] is the oracle: OKX's own realized PnL summed over positions closed today
     * (`uTime >= dayStartMs`). The components are surfaced so a gap can be attributed precisely —
     * if `realizedPnlSum == pnlSum + feeSum + fundingFeeSum + liqPenaltySum` holds, the OKX identity
     * checks out and any bills-vs-oracle gap is on the bills side.
     */
    data class PositionsHistoryBreakdown(
        val dayStartMs: Long,
        val includedCount: Int,
        val realizedPnlSum: BigDecimal,
        val pnlSum: BigDecimal,
        val feeSum: BigDecimal,
        val fundingFeeSum: BigDecimal,
        val liqPenaltySum: BigDecimal,
        val byCcy: Map<String, BigDecimal>,
    )

    /**
     * The reconciliation result. The deltas are the decision inputs:
     * - [billsVsOracleDelta] near zero  → the cap's bills-`pnl` sum already matches OKX realized PnL.
     * - [billsPlusFeeVsOracleDelta] near zero → OKX folds fees into realizedPnl but bills splits them
     *   out; the cap UNDER-reports losses by the fee total and `realizedPnlContribution` should add `fee`.
     * - non-USD entries in [PositionsHistoryBreakdown.byCcy] → the cap sums mixed currencies as if USD.
     */
    data class ReconciliationReport(
        val dayStartMs: Long,
        val bills: BillsBreakdown,
        val positions: PositionsHistoryBreakdown,
        val uiFigureUsd: BigDecimal?,
    ) {
        val billsVsOracleDelta: BigDecimal get() = bills.totalPnl.subtract(positions.realizedPnlSum)
        val billsPlusFeeVsOracleDelta: BigDecimal
            get() = bills.totalPnl.add(bills.totalFee).subtract(positions.realizedPnlSum)
        val uiVsBillsDelta: BigDecimal? get() = uiFigureUsd?.subtract(bills.totalPnl)

        /** OKX's documented identity realizedPnl = pnl + fee + fundingFee + liqPenalty, as a residual. */
        val oracleIdentityResidual: BigDecimal
            get() = positions.realizedPnlSum
                .subtract(positions.pnlSum)
                .subtract(positions.feeSum)
                .subtract(positions.fundingFeeSum)
                .subtract(positions.liqPenaltySum)

        fun render(): String = buildString {
            val day = runCatching { Instant.ofEpochMilli(dayStartMs).toString() }.getOrElse { dayStartMs.toString() }
            appendLine("=== B0 Daily Realized-PnL Reconciliation ===")
            appendLine("UTC day start: $day ($dayStartMs ms)")
            appendLine()
            appendLine("-- BILLS (what the daily-loss cap uses) --")
            appendLine("  bills today: ${bills.includedCount}  (excluded before midnight: ${bills.excludedCount})")
            appendLine("  SUM pnl  = ${bills.totalPnl.toPlainString()}   <-- the cap's daily realized PnL")
            appendLine("  SUM fee  = ${bills.totalFee.toPlainString()}   (diagnostic; NOT in the cap today)")
            appendLine("  by (type / subType / ccy):")
            if (bills.groups.isEmpty()) appendLine("    (none)")
            for (g in bills.groups) {
                appendLine(
                    "    type=${g.type.ifBlank { "?" }} sub=${g.subType.ifBlank { "?" }} ccy=${g.ccy.ifBlank { "?" }}" +
                        "  n=${g.count}  pnl=${g.pnlSum.toPlainString()}  fee=${g.feeSum.toPlainString()}"
                )
            }
            appendLine()
            appendLine("-- POSITIONS-HISTORY (independent OKX oracle) --")
            appendLine("  positions closed today: ${positions.includedCount}")
            appendLine("  SUM realizedPnl = ${positions.realizedPnlSum.toPlainString()}   <-- the oracle")
            appendLine(
                "    components: pnl=${positions.pnlSum.toPlainString()} fee=${positions.feeSum.toPlainString()} " +
                    "fundingFee=${positions.fundingFeeSum.toPlainString()} liqPenalty=${positions.liqPenaltySum.toPlainString()}"
            )
            appendLine("    realizedPnl identity residual (want ~0): ${oracleIdentityResidual.toPlainString()}")
            appendLine("  realizedPnl by ccy: " + positions.byCcy.entries.joinToString { "${it.key}=${it.value.toPlainString()}" })
            appendLine()
            appendLine("-- DELTAS (the decision) --")
            appendLine("  bills.pnl        - oracle = ${billsVsOracleDelta.toPlainString()}")
            appendLine("  bills.(pnl+fee)  - oracle = ${billsPlusFeeVsOracleDelta.toPlainString()}")
            uiFigureUsd?.let {
                appendLine("  OKX UI figure            = ${it.toPlainString()}")
                appendLine("  UI - bills.pnl           = ${uiVsBillsDelta!!.toPlainString()}")
            } ?: appendLine("  OKX UI figure            = (not supplied)")
            appendLine()
            appendLine(interpretation())
        }

        private fun interpretation(): String {
            val nonUsd = positions.byCcy.keys.filterNot { it.equals("USDT", true) || it.equals("USD", true) || it.equals("USDC", true) }
            val sb = StringBuilder("-- READ-ME (hypotheses, not verdicts) --\n")
            sb.appendLine("  Bills and positions-history are two accounting VIEWS; they reconcile cleanly ONLY on a")
            sb.appendLine("  flat-to-flat day (no position carried in from yesterday, none still open at capture).")
            sb.appendLine("  Funding on a still-open position is a bill today with no oracle row; funding on a carried-in")
            sb.appendLine("  position rolls fully into today's realizedPnl while its bills fall outside today's window.")
            sb.appendLine("  Treat any residual as funding-attribution noise UNLESS captured flat-to-flat.")
            if (nonUsd.isNotEmpty()) {
                sb.appendLine("  ! Non-USD settlement currencies present: $nonUsd. The cap sums these as if USD.")
            }
            val tol = BigDecimal("0.01")
            when {
                billsVsOracleDelta.abs() <= tol ->
                    sb.appendLine("  -> bills.pnl already matches the oracle. The cap's realized-PnL input looks correct.")
                billsPlusFeeVsOracleDelta.abs() <= tol ->
                    sb.appendLine("  -> HYPOTHESIS (confirm on a flat-to-flat day): the gap equals the fee total " +
                        "(${bills.totalFee.toPlainString()}) — i.e. the oracle folds fees into realizedPnl and the cap omits them. " +
                        "Only if it holds with NO open/carried positions, make realizedPnlContribution() add `fee`.")
                else ->
                    sb.appendLine("  -> Gap not explained by fees alone (${billsVsOracleDelta.toPlainString()}). " +
                        "Check for open/carried positions (funding noise) FIRST; then the per-(type/subType) rows and ccy.")
            }
            return sb.toString().trimEnd()
        }
    }

    fun breakdownBills(bills: List<OkxBillData>, dayStartMs: Long): BillsBreakdown {
        val today = bills.filter { it.timestamp.toMsOrMin() >= dayStartMs }
        val groups = today
            .groupBy { Triple(it.type, it.subType, it.currency) }
            .map { (key, rows) ->
                BillGroup(
                    type = key.first,
                    subType = key.second,
                    ccy = key.third,
                    count = rows.size,
                    pnlSum = rows.fold(BigDecimal.ZERO) { acc, b -> acc + b.pnl.toBd() },
                    feeSum = rows.fold(BigDecimal.ZERO) { acc, b -> acc + b.fee.toBd() },
                )
            }
            .sortedWith(compareByDescending<BillGroup> { it.count }.thenBy { it.type }.thenBy { it.subType })
        return BillsBreakdown(
            dayStartMs = dayStartMs,
            includedCount = today.size,
            excludedCount = bills.size - today.size,
            totalPnl = today.fold(BigDecimal.ZERO) { acc, b -> acc + b.pnl.toBd() },
            totalFee = today.fold(BigDecimal.ZERO) { acc, b -> acc + b.fee.toBd() },
            groups = groups,
        )
    }

    fun breakdownPositionsHistory(positions: List<OkxPositionHistoryData>, dayStartMs: Long): PositionsHistoryBreakdown {
        val today = positions.filter { it.updatedTime.toMsOrMin() >= dayStartMs }
        val byCcy = today
            .groupBy { it.currency }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, p -> acc + p.realizedPnl.toBd() } }
        return PositionsHistoryBreakdown(
            dayStartMs = dayStartMs,
            includedCount = today.size,
            realizedPnlSum = today.fold(BigDecimal.ZERO) { acc, p -> acc + p.realizedPnl.toBd() },
            pnlSum = today.fold(BigDecimal.ZERO) { acc, p -> acc + p.pnl.toBd() },
            feeSum = today.fold(BigDecimal.ZERO) { acc, p -> acc + p.fee.toBd() },
            fundingFeeSum = today.fold(BigDecimal.ZERO) { acc, p -> acc + p.fundingFee.toBd() },
            liqPenaltySum = today.fold(BigDecimal.ZERO) { acc, p -> acc + p.liqPenalty.toBd() },
            byCcy = byCcy,
        )
    }

    fun reconcile(
        bills: List<OkxBillData>,
        positions: List<OkxPositionHistoryData>,
        dayStartMs: Long,
        uiFigureUsd: BigDecimal? = null,
    ): ReconciliationReport = ReconciliationReport(
        dayStartMs = dayStartMs,
        bills = breakdownBills(bills, dayStartMs),
        positions = breakdownPositionsHistory(positions, dayStartMs),
        uiFigureUsd = uiFigureUsd,
    )
}

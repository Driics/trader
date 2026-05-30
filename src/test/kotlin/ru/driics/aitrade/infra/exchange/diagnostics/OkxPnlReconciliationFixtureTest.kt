package ru.driics.aitrade.infra.exchange.diagnostics

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.OkxBillData
import ru.driics.aitrade.domain.model.OkxPositionHistoryData
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * B0 reconciliation against REAL captured OKX data (the verification step).
 *
 * SKIPPED — not failed — unless `./data/okx-capture/` has been populated by [OkxPnlCaptureRunner]. So
 * it ships green and never blocks a normal build. When a capture is present it:
 *   1. prints the full reconciliation breakdown (and writes it to reconciliation-report.txt), and
 *   2. only HARD-asserts a tolerance once the operator has reconciled and committed one to
 *      oracle.properties (`max.abs.delta.usd`).
 *
 * It deliberately does NOT fail on a non-zero bills-vs-oracle delta by default: surfacing that gap is
 * the whole purpose of the harness. Locking the tolerance is the operator's explicit, post-review act.
 */
class OkxPnlReconciliationFixtureTest {

    private val dir: Path = Path.of("./data/okx-capture")
    private val mapper = jacksonObjectMapper()

    @Test
    fun `reconcile captured bills against the positions-history oracle`() {
        val billsFile = dir.resolve("bills.json")
        val positionsFile = dir.resolve("positions-history.json")
        val metaFile = dir.resolve("capture-meta.json")
        assumeTrue(
            Files.exists(billsFile) && Files.exists(positionsFile) && Files.exists(metaFile),
            "No capture in ./data/okx-capture/. Run: gradlew bootRun --args='--spring.profiles.active=capture-okx'",
        )

        val bills: List<OkxBillData> = mapper.readValue(Files.readString(billsFile))
        val positions: List<OkxPositionHistoryData> = mapper.readValue(Files.readString(positionsFile))
        val meta: Map<String, Any> = mapper.readValue(Files.readString(metaFile))
        val dayStartMs = (meta["dayStartMs"] as Number).toLong()

        val oracle = loadOracle()
        val report = PnlReconciliation.reconcile(bills, positions, dayStartMs, uiFigureUsd = oracle.uiFigure)

        val rendered = report.render()
        println(rendered)
        // Also persist it — gradle may swallow stdout; this file is the reliable artifact to read.
        runCatching { Files.writeString(dir.resolve("reconciliation-report.txt"), rendered) }

        val tol = oracle.maxAbsDeltaUsd
        if (tol == null) {
            println(
                "[reconciliation] No tolerance set. After reviewing the breakdown, add " +
                    "max.abs.delta.usd=<n> to ./data/okx-capture/oracle.properties to turn this into a regression guard.",
            )
            return
        }
        val best = report.billsVsOracleDelta.abs().min(report.billsPlusFeeVsOracleDelta.abs())
        assertTrue(
            best <= tol,
            "Bills realized-PnL diverges from the OKX oracle by ${best.toPlainString()} USD (tolerance ${tol.toPlainString()}). " +
                "See reconciliation-report.txt — adjust OkxExchangeAdapter.realizedPnlContribution() or the tolerance.",
        )
    }

    private data class Oracle(val uiFigure: BigDecimal?, val maxAbsDeltaUsd: BigDecimal?)

    private fun loadOracle(): Oracle {
        val f = dir.resolve("oracle.properties")
        if (!Files.exists(f)) return Oracle(null, null)
        val props = Properties().apply { Files.newInputStream(f).use { load(it) } }
        return Oracle(
            uiFigure = props.getProperty("ui.realizedPnlUsd")?.trim()?.toBigDecimalOrNull(),
            maxAbsDeltaUsd = props.getProperty("max.abs.delta.usd")?.trim()?.toBigDecimalOrNull(),
        )
    }
}

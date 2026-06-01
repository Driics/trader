package ru.driics.aitrade.infra.exchange.diagnostics

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.model.OkxBillData
import ru.driics.aitrade.domain.model.OkxPositionHistoryData
import ru.driics.aitrade.service.OkxRestClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

/**
 * Operator-run, one-shot capture of real `/account/bills` + `/account/positions-history` payloads for
 * B0 PnL reconciliation. Active ONLY under the `capture-okx` Spring profile, so it is inert in every
 * normal run and in the default test context (it will not load during `contextLoads`).
 *
 * Why a runner and not a curl: it reuses the app's signed OKX clients, so the operator needs no manual
 * request signing. It writes to a GITIGNORED folder (`./data/okx-capture/`) because the payloads are
 * real account data — review/scrub before sharing.
 *
 * Safety: the `capture-okx` profile (application-capture-okx.yml) forces `trading.auto-execute=false`
 * and pushes the scheduler delay out, so no orders can be placed while capturing. The runner exits the
 * JVM when done.
 *
 * Run:  `./gradlew.bat bootRun --args='--spring.profiles.active=capture-okx'`
 * Then: run [ru.driics.aitrade.infra.exchange.diagnostics] OkxPnlReconciliationFixtureTest.
 */
@Component
@Profile("capture-okx")
class OkxPnlCaptureRunner(
    private val rest: OkxRestClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : ApplicationRunner {

    private val log = logger<OkxPnlCaptureRunner>()

    override fun run(args: ApplicationArguments) {
        try {
            val outDir = Path.of(OUTPUT_DIR)
            runBlocking { capture(outDir) }
            log.info {
                "CAPTURE COMPLETE -> $OUTPUT_DIR/. Review/scrub the JSON (it is real account data), optionally " +
                    "add your OKX UI realized-PnL figure to oracle.properties, then run OkxPnlReconciliationFixtureTest. Stopping."
            }
            exitProcess(0)
        } catch (e: Exception) {
            log.error(e) { "OKX PnL capture FAILED — nothing trustworthy was written" }
            exitProcess(1)
        }
    }

    private suspend fun capture(outDir: Path) {
        Files.createDirectories(outDir)
        val capturedAtMs = clock.instant().toEpochMilli()
        val dayStartMs = clock.instant().truncatedTo(ChronoUnit.DAYS).toEpochMilli()

        val billRecords = collectBills()
        val positionRecords = collectPositions()

        writeJson(outDir.resolve(FILE_BILLS), billRecords)
        writeJson(outDir.resolve(FILE_POSITIONS), positionRecords)
        writeJson(
            outDir.resolve(FILE_META),
            mapOf(
                "capturedAtMs" to capturedAtMs,
                "dayStartMs" to dayStartMs,
                "billCount" to billRecords.size,
                "positionCount" to positionRecords.size,
                "note" to "dayStartMs = UTC midnight at capture time; the reconciliation uses this exact window.",
            ),
        )
        log.info { "Captured ${billRecords.size} bills, ${positionRecords.size} closed positions (UTC dayStart=$dayStartMs)" }
    }

    private suspend fun collectBills(): List<OkxBillData> {
        val acc = mutableListOf<OkxBillData>()
        var after: String? = null
        var page = 0
        while (page < MAX_PAGES) {
            page++
            val resp = rest.fetchBills(after = after, limit = PAGE_LIMIT)
                ?: throw IllegalStateException("bills read failed on page $page (fail-closed)")
            require(resp.isSuccess()) { "bills API error code=${resp.code} msg=${resp.message}" }
            if (resp.data.isEmpty()) break
            acc += resp.data
            if (resp.data.size < PAGE_LIMIT) break
            after = resp.data.last().billId
        }
        return acc
    }

    private suspend fun collectPositions(): List<OkxPositionHistoryData> {
        val acc = mutableListOf<OkxPositionHistoryData>()
        var after: String? = null
        var page = 0
        while (page < MAX_PAGES) {
            page++
            val resp = rest.fetchPositionsHistory(after = after, limit = PAGE_LIMIT)
                ?: throw IllegalStateException("positions-history read failed on page $page (fail-closed)")
            require(resp.isSuccess()) { "positions-history API error code=${resp.code} msg=${resp.message}" }
            if (resp.data.isEmpty()) break
            acc += resp.data
            if (resp.data.size < PAGE_LIMIT) break
            after = resp.data.last().posId
        }
        return acc
    }

    private fun writeJson(path: Path, value: Any) {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))
    }

    private companion object {
        const val OUTPUT_DIR = "./data/okx-capture"
        const val FILE_BILLS = "bills.json"
        const val FILE_POSITIONS = "positions-history.json"
        const val FILE_META = "capture-meta.json"
        const val PAGE_LIMIT = 100
        const val MAX_PAGES = 10
    }
}

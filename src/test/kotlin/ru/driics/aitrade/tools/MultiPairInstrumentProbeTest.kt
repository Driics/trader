package ru.driics.aitrade.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.domain.types.getOrNull

/**
 * Live probe: loads each configured currency's instrument from REAL OKX through the production path
 * (InstrumentResolver -> TradingPort.loadInstrument) and prints the per-instrument specs. It proves
 * the config-driven multi-pair path end to end and that the per-instrument leverage cap (M1) is
 * populated and varies per pair (alts cap below majors).
 *
 * Hits only the OKX public instruments endpoint (no AI cost) but loads the full Spring context, so it
 * is gated behind MULTIPAIR_PROBE and lives in the tools package away from the fast domain tests.
 *
 * Run (PowerShell):
 *   $env:MULTIPAIR_PROBE="1"
 *   .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*MultiPairInstrumentProbeTest*'
 */
@SpringBootTest
class MultiPairInstrumentProbeTest {

    @Autowired lateinit var trading: TradingPort
    @Autowired lateinit var instrumentResolver: InstrumentResolver
    @Autowired lateinit var tradingProperties: TradingProperties

    @Test
    fun `every configured pair resolves to a live instrument with a populated leverage cap`() = runBlocking {
        assumeTrue(
            System.getenv("MULTIPAIR_PROBE")?.isNotBlank() == true,
            "set MULTIPAIR_PROBE=1 to probe live OKX instruments",
        )

        val symbols = tradingProperties.getCurrenciesList()
        assumeTrue(symbols.isNotEmpty(), "no trading.currencies configured")

        val configMax = tradingProperties.maxLeverage
        println("config maxLeverage=$configMax  quote=${tradingProperties.quoteCurrency}  type=${tradingProperties.instrumentType}")
        println("%-6s %-16s %-6s %-7s %-9s %-7s %s".format("sym", "instId", "lever", "effCap", "tickSz", "lotSz", "ctVal"))

        var pairsWithLever = 0
        for (sym in symbols) {
            val instId = instrumentResolver.instrumentId(sym)
            val inst = trading.loadInstrument(instId).getOrNull()
            assertNotNull(inst, "loadInstrument returned null for $instId")
            val lever = inst!!.lever?.toIntOrNull()
            if (lever != null) pairsWithLever++
            // effCap is exactly what ActionGuard now enforces: min(instrument cap, configured max).
            val effCap = lever?.let { minOf(it, configMax) } ?: configMax
            println(
                "%-6s %-16s %-6s %-7s %-9s %-7s %s".format(
                    sym, instId.value, inst.lever ?: "-", effCap, inst.tickSz ?: "-", inst.lotSz ?: "-", inst.ctVal ?: "-",
                ),
            )
        }

        assertTrue(
            pairsWithLever == symbols.size,
            "OKX must return a leverage cap for every pair (the M1 fix's input); got $pairsWithLever/${symbols.size}",
        )
    }
}

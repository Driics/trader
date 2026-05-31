package ru.driics.aitrade.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.domain.types.OrderSide
import ru.driics.aitrade.domain.types.getOrThrow
import java.math.BigDecimal

/**
 * Places ONE minimum-size order on OKX's DEMO environment through the real adapter, proving the
 * x-simulated-trading paper path end to end (OKX accepts the demo header and fills the order). This is
 * the only way to confirm the header is correct — a unit test can only assert it's present, not accepted.
 *
 * HARD SAFETY: it refuses to run unless `okx.paper=true`, so it can never place a LIVE order. It also
 * needs PAPER_ORDER_PROBE=1, the full Spring context, OKX DEMO API keys, and OKX_BROKER_ID. It OPENS a
 * tiny demo position (close it in the OKX demo UI afterwards).
 *
 * Run (PowerShell) — with DEMO keys configured:
 *   $env:OKX_PAPER="true"; $env:OKX_BROKER_ID="<your demo broker id>"; $env:PAPER_ORDER_PROBE="1"
 *   .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*PaperOrderHarnessTest*'
 */
@SpringBootTest
class PaperOrderHarnessTest {

    @Autowired lateinit var trading: TradingPort
    @Autowired lateinit var instrumentResolver: InstrumentResolver
    @Autowired lateinit var tradingProperties: TradingProperties
    @Autowired lateinit var okxProperties: OkxProperties

    @Test
    fun `place one minimum-size paper order on OKX demo`() = runBlocking {
        assumeTrue(
            System.getenv("PAPER_ORDER_PROBE")?.isNotBlank() == true,
            "set PAPER_ORDER_PROBE=1 to place ONE tiny order on OKX DEMO (needs DEMO keys + okx.paper=true)",
        )
        // The one safety gate that matters: never place a non-paper order from a test.
        assumeTrue(okxProperties.paper, "REFUSING: okx.paper must be true (demo). This harness never touches LIVE.")

        val symbols = tradingProperties.getCurrenciesList()
        assumeTrue(symbols.isNotEmpty(), "no trading.currencies configured")

        val symbol = symbols.first()
        val instId = instrumentResolver.instrumentId(symbol)
        val inst = trading.loadInstrument(instId).getOrThrow()
        val contracts = inst.minSz?.toBigDecimalOrNull()
            ?: inst.lotSz?.toBigDecimalOrNull()
            ?: BigDecimal.ONE
        val tickSz = inst.tickSz?.toBigDecimalOrNull() ?: BigDecimal("0.01")
        val marginMode = tradingProperties.getMarginMode()

        // The x-simulated-trading header (okx.paper=true) is the actual gate that routes this to OKX's
        // demo engine; demo keys are best practice, not the safety boundary.
        println("PAPER probe -> $instId  contracts=$contracts tickSz=$tickSz margin=$marginMode (okx.paper=true -> OKX DEMO)")

        val levOk = trading.setLeverage(instId, 3, marginMode).getOrThrow()
        assertTrue(levOk, "setLeverage on OKX demo failed")

        val outcome = trading.placeMarketOrderWithTpSl(
            instrumentId = instId,
            side = OrderSide.BUY,
            contracts = contracts,
            tp = null,
            sl = null,
            tickSz = tickSz,
            clOrdId = "pp" + System.currentTimeMillis(),
            tag = "paper-probe",
            marginMode = marginMode,
        ).getOrThrow()

        println("PAPER ORDER -> ok=${outcome.ok} ordId=${outcome.ordId} msg=${outcome.message}")
        assertTrue(outcome.ok, "OKX demo rejected the paper order: ${outcome.message}")
    }
}

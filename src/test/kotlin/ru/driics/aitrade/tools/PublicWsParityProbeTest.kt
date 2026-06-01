package ru.driics.aitrade.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import ru.driics.aitrade.application.usecase.priceDeltaBps
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.domain.types.getOrNull
import java.math.BigDecimal

/**
 * Live PUBLIC-data parity probe for Phase 3's streaming entry-price source. It is the evidence path
 * the `useStreamingEntryPrice` flag is gated on: it shows the fresh WS price tracking REST by a small,
 * stable delta over a real run, WITHOUT placing an order or spending AI budget.
 *
 * Why a standalone probe (not the in-loop `price-parity` log): that log only fires under
 * autoExecute=true on a non-HOLD, above-confidence signal — i.e. it needs the AI budget and produces
 * sparse samples. This probe reads only OKX's PUBLIC websocket + public REST and places no orders, so
 * it is the parity-evidence path that is never blocked. It shares the standard tools/ probe Spring
 * context (plain @SpringBootTest, so it adds no extra context boot to the suite); run it with a DUMMY
 * OPENROUTER_API_KEYS so the one scheduled cycle that may fire cannot make a real (billed) AI call.
 *
 * It connects through the production path: the autowired [StreamingMarketDataPort] is the real
 * OkxStreamingAdapter, whose @PostConstruct subscribes the configured currencies' tickers; we then
 * poll [StreamingMarketDataPort.getFreshPrice] (the exact call buildPlan makes) and compare to
 * [TradingPort.getLastPrice] using the production [priceDeltaBps]. Like the other tools/ probes it
 * loads the full Spring context, so it is gated behind WS_PARITY_PROBE and assertions are generous
 * (live prices legitimately move between the WS read and the REST read).
 *
 * Run (PowerShell):
 *   $env:WS_PARITY_PROBE="1"
 *   $env:OPENROUTER_API_KEYS="dummy"   # context needs a non-empty value to boot; keep it a dummy so no real AI call is billed
 *   .\gradlew.bat --rerun-tasks test --tests '*PublicWsParityProbeTest*'
 *
 * A null fresh price within the connect window is NOT a code failure — it means the socket did not
 * connect/tick from this environment, which is exactly the safe REST-fallback case the flag relies
 * on. The probe fails that case loudly only so the absence of evidence is never mistaken for evidence.
 */
@SpringBootTest
class PublicWsParityProbeTest {

    @Autowired lateinit var streaming: StreamingMarketDataPort
    @Autowired lateinit var trading: TradingPort
    @Autowired lateinit var instrumentResolver: InstrumentResolver
    @Autowired lateinit var tradingProperties: TradingProperties

    private companion object {
        const val FRESH_MAX_AGE_MS = 10_000L     // generous freshness window for the probe
        const val CONNECT_TIMEOUT_MS = 30_000L   // max wait for the socket to connect + first tick
        const val POLL_INTERVAL_MS = 500L
        const val SAMPLE_COUNT = 6
        const val SAMPLE_GAP_MS = 2_000L
        // 1% — deliberately loose so normal volatility between the WS and REST reads never flakes it.
        // The point is to catch a feed that is wildly wrong, not to assert tick-level agreement.
        val MAX_TOLERATED_DELTA_BPS: BigDecimal = BigDecimal("100")
    }

    @Test
    fun `fresh WS price tracks REST within a generous band over a short live run`() = runBlocking {
        assumeTrue(
            System.getenv("WS_PARITY_PROBE")?.isNotBlank() == true,
            "set WS_PARITY_PROBE=1 to run the live public-WS parity probe",
        )

        val symbol = tradingProperties.getCurrenciesList().firstOrNull()
        assumeTrue(symbol != null, "no trading.currencies configured")
        val instId = instrumentResolver.instrumentId(symbol!!)

        // 1. Wait (bounded) for the public socket to connect AND deliver a tick fresh enough to pass
        //    getFreshPrice — i.e. the exact precondition buildPlan needs before it will trust WS.
        var waited = 0L
        var firstFresh: BigDecimal? = null
        while (waited < CONNECT_TIMEOUT_MS) {
            firstFresh = streaming.getFreshPrice(instId.value, FRESH_MAX_AGE_MS)
            if (firstFresh != null) break
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        assertNotNull(
            firstFresh,
            "no fresh WS price for ${instId.value} within ${CONNECT_TIMEOUT_MS}ms — the public socket " +
                "did not connect/tick from this environment. The feature would safely fall back to REST, " +
                "but there is no parity evidence to justify enabling it here.",
        )
        println("ws-parity ${instId.value}: connected, first fresh price=$firstFresh (waited ${waited}ms)")

        // 2. Sample WS-vs-REST a handful of times and print the deltaBps that step 3 is judged on.
        var paired = 0
        var maxDeltaBps = BigDecimal.ZERO
        repeat(SAMPLE_COUNT) { i ->
            val ws = streaming.getFreshPrice(instId.value, FRESH_MAX_AGE_MS)
            val rest = trading.getLastPrice(instId).getOrNull()
            if (ws != null && rest != null) {
                val deltaBps = priceDeltaBps(ws, rest)
                if (deltaBps > maxDeltaBps) maxDeltaBps = deltaBps
                paired++
                println("  sample[$i] ws=$ws rest=$rest deltaBps=$deltaBps")
            } else {
                println("  sample[$i] ws=$ws rest=$rest (unpaired — skipped)")
            }
            delay(SAMPLE_GAP_MS)
        }

        assertTrue(paired > 0, "collected no paired WS+REST samples over $SAMPLE_COUNT attempts")
        assertTrue(
            maxDeltaBps <= MAX_TOLERATED_DELTA_BPS,
            "max WS-vs-REST deltaBps=$maxDeltaBps exceeded $MAX_TOLERATED_DELTA_BPS over $paired samples — " +
                "investigate the feed before enabling useStreamingEntryPrice.",
        )
        println("ws-parity ${instId.value}: PASS ($paired paired samples, maxDeltaBps=$maxDeltaBps)")
    }
}

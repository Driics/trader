package ru.driics.aitrade.application.usecase

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.risk.RiskGate
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot
import ru.driics.aitrade.domain.risk.RiskContext
import ru.driics.aitrade.domain.risk.RiskDecision
import ru.driics.aitrade.config.RiskGateProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.journal.JournaledOrder
import ru.driics.aitrade.domain.model.AIAction
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.PlaceOrderOutcome
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.domain.types.TradeResult
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Characterization tests for the P2 change: execute() now takes the cycle's MarketState snapshot
 * instead of re-loading it. These pin the skip-pipeline (which does not depend on ActionGuard /
 * real order placement) and confirm the new 3-arg signature. Placement-path behaviour is covered
 * by later tranches once a deterministic plan fixture exists.
 */
class ExecuteAiDecisionsUseCaseTest {

    private val clock = Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC)
    private val trading = mockk<TradingPort>(relaxed = true)
    private val confidenceCalibrator = mockk<ConfidenceCalibrator>(relaxed = true)
    private val riskGate = mockk<RiskGate>(relaxed = true)
    private val streaming = mockk<StreamingMarketDataPort>(relaxed = true)
    private val tradeJournal = mockk<TradeJournalPort>(relaxed = true)
    private val meterRegistry = SimpleMeterRegistry()

    private val props = TradingProperties(currencies = listOf("BTC"))

    private fun useCase(tradingProperties: TradingProperties = props) = ExecuteAiDecisionsUseCase(
        trading = trading,
        tradingProperties = tradingProperties,
        clock = clock,
        confidenceCalibrator = confidenceCalibrator,
        meterRegistry = meterRegistry,
        riskGate = riskGate,
        riskGateProperties = RiskGateProperties(),
        streaming = streaming,
        instrumentResolver = InstrumentResolver(tradingProperties.quoteCurrency, tradingProperties.instrumentType),
        tradeJournal = tradeJournal,
        tradingMode = TradingMode.SIMULATION,
    )

    private fun snapshot(
        availableCash: BigDecimal = BigDecimal("1000"),
    ) = MarketState(
        timestamp = 0L,
        minutesSinceStart = 0L,
        invocationCount = 1L,
        currencies = emptyMap(),
        account = AccountInfo(
            totalReturn = BigDecimal.ZERO,
            availableCash = availableCash,
            accountValue = availableCash,
        ),
        positions = emptyList(),
    )

    private val riskContext = RiskContext(
        openPositionsCount = 0,
        todaysRealizedPnlUsd = BigDecimal.ZERO,
        killSwitch = KillSwitchSnapshot.disabled(),
    )

    private fun envelope(
        coin: String,
        signal: AiSignal,
        confidence: BigDecimal? = BigDecimal("0.9"),
    ) = AiTradeEnvelope(
        AiTradeSignalArgs(
            coin = coin,
            signal = signal,
            confidence = confidence,
            riskUsd = BigDecimal("10"),
            stopLoss = BigDecimal("60000"),
            profitTarget = BigDecimal("70000"),
            leverage = 5,
        ),
    )

    @Test
    fun `no decisions yields no results`() = runBlocking {
        val results = useCase().execute(emptyMap(), riskContext, snapshot())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `unsupported symbol is skipped (no result emitted)`() = runBlocking {
        val decisions = mapOf("ETH" to envelope("ETH", AiSignal.BUY))
        val results = useCase().execute(decisions, riskContext, snapshot())
        assertTrue(results.isEmpty(), "skips are filtered out before execution, not emitted")
    }

    @Test
    fun `HOLD signal is skipped`() = runBlocking {
        val decisions = mapOf("BTC" to envelope("BTC", AiSignal.HOLD))
        val results = useCase().execute(decisions, riskContext, snapshot())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `below-min-confidence signal is skipped`() = runBlocking {
        val decisions = mapOf("BTC" to envelope("BTC", AiSignal.BUY, confidence = BigDecimal("0.10")))
        val results = useCase().execute(decisions, riskContext, snapshot())
        assertTrue(results.isEmpty())
    }

    // =========================================================================
    // S6 — demo-mode side-effect isolation (placement path)
    // =========================================================================

    private val instrument = OkxInstrumentInfo(
        instId = "BTC-USDT-SWAP",
        instType = "SWAP",
        ctVal = "0.01",
        ctValCcy = "BTC",
        lotSz = "1",
        minSz = "1",
        tickSz = "0.1",
    )

    /** A BUY that clears ActionGuard: SL below entry (unfavorable), TP above (favorable). */
    private fun readyBuy() = AiTradeEnvelope(
        AiTradeSignalArgs(
            coin = "BTC",
            signal = AiSignal.BUY,
            confidence = BigDecimal("0.9"),
            riskUsd = BigDecimal("100"),   // 100 / |50000-40000| = 0.01 coin -> 1 contract
            stopLoss = BigDecimal("40000"),
            profitTarget = BigDecimal("70000"),
            leverage = 5,
        ),
    )

    private fun stubReadyPlacementPath() {
        every { riskGate.evaluate(any()) } returns RiskDecision.Allow
        coEvery { trading.loadInstrument(any()) } returns TradeResult.Success(instrument)
        coEvery { trading.getLastPrice(any()) } returns TradeResult.Success(BigDecimal("50000"))
        coEvery { trading.setLeverage(any(), any(), any()) } returns TradeResult.Success(true)
    }

    @Test
    fun `demo placement does NOT record the trade or mutate calibration (S6)`() = runBlocking {
        stubReadyPlacementPath() // props default -> demoMode = true

        val results = useCase().execute(mapOf("BTC" to readyBuy()), riskContext, snapshot(BigDecimal("100000")))

        assertEquals(1, results.size)
        assertEquals(AIAction.PLACED, results.single().action, "demo order should be simulated as placed")
        verify(exactly = 0) { confidenceCalibrator.recordTrade(any()) }
    }

    @Test
    fun `real placement DOES record the trade (contrast with demo)`() = runBlocking {
        stubReadyPlacementPath()
        coEvery {
            trading.placeMarketOrderWithTpSl(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns TradeResult.Success(PlaceOrderOutcome(ok = true, ordId = "OID-1", message = "Placed"))

        val realProps = TradingProperties(currencies = listOf("BTC"), demoMode = false)
        val results = useCase(realProps).execute(mapOf("BTC" to readyBuy()), riskContext, snapshot(BigDecimal("100000")))

        assertEquals(1, results.size)
        assertEquals(AIAction.PLACED, results.single().action)
        verify(exactly = 1) { confidenceCalibrator.recordTrade("BTC") }
    }

    // =========================================================================
    // Trade journal — order recording hooks
    // =========================================================================

    @Test
    fun `demo placement records a PLACED order in the trade journal exactly once`() = runBlocking {
        stubReadyPlacementPath() // props default -> demoMode = true

        val results = useCase().execute(mapOf("BTC" to readyBuy()), riskContext, snapshot(BigDecimal("100000")))

        assertEquals(1, results.size)
        assertEquals(AIAction.PLACED, results.single().action)
        verify(exactly = 1) { tradeJournal.recordOrder(match<JournaledOrder> { it.status == "PLACED" }) }
    }

    @Test
    fun `exchange rejection of a real order records a REJECTED order in the trade journal`() = runBlocking {
        stubReadyPlacementPath()
        coEvery {
            trading.placeMarketOrderWithTpSl(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns TradeResult.Success(PlaceOrderOutcome(ok = false, ordId = null, message = "insufficient balance"))

        val realProps = TradingProperties(currencies = listOf("BTC"), demoMode = false)
        val results = useCase(realProps).execute(mapOf("BTC" to readyBuy()), riskContext, snapshot(BigDecimal("100000")))

        assertEquals(1, results.size)
        assertEquals(AIAction.SKIPPED, results.single().action)
        verify(exactly = 1) {
            tradeJournal.recordOrder(match<JournaledOrder> { it.status == "REJECTED" && it.reason == "insufficient balance" })
        }
    }

    // =========================================================================
    // Phase 3 step 3 — useStreamingEntryPrice end-to-end (flag drives the sizing input)
    //
    // The pure selector is pinned by EntryPriceSelectionTest; these prove the WIRING: with the flag
    // on, a fresh in-band WS price actually reaches sizing through buildPlan (observable as the
    // journaled entryPx, which is quantize(resolveEntryPrice(...)) ). The flag-on vs flag-off contrast
    // on the SAME WS stub is self-validating — if entryPx didn't track the resolved price, the first
    // test would fail rather than pass falsely. getFreshPrice is called twice per plan (parity log +
    // resolve), so these never assert a call-count on it.
    // =========================================================================

    @Test
    fun `Phase 3 - flag on, a fresh in-band WS price sizes the order instead of REST`() = runBlocking {
        stubReadyPlacementPath() // REST getLastPrice = 50000
        // 49990 is ~2 bps off REST -> within the 0.5% trust band -> the WS price wins. It sits just
        // BELOW REST on purpose: readyBuy sizes off risk (riskUsd / |entry - SL|), so an entry ABOVE
        // 50000 would round the contract count below 1 and skip placement; below keeps the 1-contract
        // fixture intact, isolating the entry-source swap as the only variable.
        every { streaming.getFreshPrice(any(), any()) } returns BigDecimal("49990")
        val streamingProps = TradingProperties(currencies = listOf("BTC"), useStreamingEntryPrice = true)

        useCase(streamingProps).execute(mapOf("BTC" to readyBuy()), riskContext, snapshot(BigDecimal("100000")))

        verify(exactly = 1) {
            tradeJournal.recordOrder(match<JournaledOrder> { it.entryPx?.compareTo(BigDecimal("49990")) == 0 })
        }
    }

    @Test
    fun `Phase 3 - flag on, a WS price beyond the trust band falls back to REST sizing`() = runBlocking {
        stubReadyPlacementPath() // REST getLastPrice = 50000
        // 51000 is ~200 bps off REST -> exceeds the 0.5% sanity belt -> REST is used (never worse).
        every { streaming.getFreshPrice(any(), any()) } returns BigDecimal("51000")
        val streamingProps = TradingProperties(currencies = listOf("BTC"), useStreamingEntryPrice = true)

        useCase(streamingProps).execute(mapOf("BTC" to readyBuy()), riskContext, snapshot(BigDecimal("100000")))

        verify(exactly = 1) {
            tradeJournal.recordOrder(match<JournaledOrder> { it.entryPx?.compareTo(BigDecimal("50000")) == 0 })
        }
    }

    @Test
    fun `Phase 3 - flag off (default), a fresh WS price is ignored and REST sizes the order`() = runBlocking {
        stubReadyPlacementPath() // REST getLastPrice = 50000
        // A perfectly fresh, in-band WS price is present, but the default-off flag must gate it out.
        every { streaming.getFreshPrice(any(), any()) } returns BigDecimal("49990")

        useCase().execute(mapOf("BTC" to readyBuy()), riskContext, snapshot(BigDecimal("100000")))

        verify(exactly = 1) {
            tradeJournal.recordOrder(match<JournaledOrder> { it.entryPx?.compareTo(BigDecimal("50000")) == 0 })
        }
    }

    // =========================================================================
    // Batch B — per-instrument leverage cap (M1) and config-driven sizing clamp (M3)
    // =========================================================================

    private fun buyWithLeverage(leverage: Int) = AiTradeEnvelope(
        AiTradeSignalArgs(
            coin = "BTC",
            signal = AiSignal.BUY,
            confidence = BigDecimal("0.9"),
            riskUsd = BigDecimal("100"),   // entry 50000, SL 40000 -> 0.01 coin -> 1 contract
            stopLoss = BigDecimal("40000"),
            profitTarget = BigDecimal("70000"),
            leverage = leverage,
        ),
    )

    @Test
    fun `M1 - leverage over the instrument cap is rejected even when within config max`() = runBlocking {
        // Instrument allows only 20x; config allows 40x. A 30x request is within config but over the
        // instrument cap -> it must be rejected here, not sent to OKX only to be bounced at placement.
        val cappedInstrument = instrument.copy(lever = "20")
        every { riskGate.evaluate(any()) } returns RiskDecision.Allow
        coEvery { trading.loadInstrument(any()) } returns TradeResult.Success(cappedInstrument)
        coEvery { trading.getLastPrice(any()) } returns TradeResult.Success(BigDecimal("50000"))
        coEvery { trading.setLeverage(any(), any(), any()) } returns TradeResult.Success(true)

        val results = useCase().execute(
            mapOf("BTC" to buyWithLeverage(30)),
            riskContext,
            snapshot(BigDecimal("100000")),
        )

        assertTrue(results.isEmpty(), "30x exceeds the instrument's 20x cap and must be skipped")
    }

    @Test
    fun `M3 - sizing leverage clamp honors a configured maxLeverage above the old hardcoded 40`() = runBlocking {
        // Config allows 60x and the instrument has no tighter cap. The leverage actually set on the
        // exchange must be 60 -- not silently clamped to OrderSizingPolicy's old hardcoded ceiling of 40.
        stubReadyPlacementPath() // returns the default instrument (no per-instrument lever cap)
        val props60 = TradingProperties(currencies = listOf("BTC"), maxLeverage = 60)

        useCase(props60).execute(
            mapOf("BTC" to buyWithLeverage(60)),
            riskContext,
            snapshot(BigDecimal("100000")),
        )

        coVerify(exactly = 1) { trading.setLeverage(any(), 60, any()) }
    }
}

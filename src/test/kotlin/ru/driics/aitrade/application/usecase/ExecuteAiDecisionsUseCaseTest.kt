package ru.driics.aitrade.application.usecase

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.risk.KillSwitchSnapshot
import ru.driics.aitrade.application.risk.RiskContext
import ru.driics.aitrade.application.risk.RiskDecision
import ru.driics.aitrade.application.risk.RiskGate
import ru.driics.aitrade.config.RiskGateProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AIAction
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import ru.driics.aitrade.domain.ports.PlaceOrderOutcome
import ru.driics.aitrade.domain.ports.TradingPort
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
}

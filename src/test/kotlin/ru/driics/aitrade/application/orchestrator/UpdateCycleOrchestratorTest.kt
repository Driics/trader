package ru.driics.aitrade.application.orchestrator

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.application.ai.AiSchemaValidator
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.risk.KillSwitchState
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import ru.driics.aitrade.application.usecase.PromptResult
import ru.driics.aitrade.config.RiskGateProperties
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.AiAnalysisResponse
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeDecisionMap
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.journal.JournaledPnlSnapshot
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.TradingMetricsService
import ru.driics.aitrade.domain.types.TradeResult
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Characterization + S1 coverage for the orchestrator's execute stage.
 *
 * Focus: the fail-CLOSED fork on an unreadable realized-PnL read. The pipeline is driven through
 * build -> analyze -> parse -> guard with mocks so execution is actually reached; the guard stage
 * uses the REAL SignalNormalizer, so the BTC fixture must normalize cleanly.
 */
class UpdateCycleOrchestratorTest {

    private val market = mockk<MarketDataPort>(relaxed = true)
    private val trading = mockk<TradingPort>(relaxed = true)
    private val killSwitchState = mockk<KillSwitchState>(relaxed = true)
    private val meterRegistry = SimpleMeterRegistry()
    private val tradingMetrics = mockk<TradingMetricsService>(relaxed = true)
    private val schemaValidator = mockk<AiSchemaValidator>()
    private val confidenceCalibrator = mockk<ConfidenceCalibrator>()
    private val tracer = mockk<Tracer>(relaxed = true)
    private val tradeJournal = mockk<TradeJournalPort>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC)

    private val build = mockk<BuildPromptUseCase>()
    private val analyze = mockk<AnalyzePromptUseCase>()
    private val execute = mockk<ExecuteAiDecisionsUseCase>()

    private val marketState = MarketState(
        timestamp = 0L,
        minutesSinceStart = 0L,
        invocationCount = 1L,
        currencies = mapOf(
            // A real price so the empty-data guard does NOT trip — these tests exercise the AI/execute path.
            "BTC" to CurrencyMarketData(
                symbol = "BTC", currentPrice = BigDecimal("65000"),
                currentEma20 = BigDecimal.ZERO, currentMacd = BigDecimal.ZERO, currentRsi7 = BigDecimal.ZERO,
            ),
        ),
        account = AccountInfo(
            totalReturn = BigDecimal.ZERO,
            availableCash = BigDecimal("1000"),
            accountValue = BigDecimal("1000"),
        ),
        positions = emptyList(),
    )

    private val decisions: AiTradeDecisionMap = mapOf(
        "BTC" to AiTradeEnvelope(
            AiTradeSignalArgs(
                coin = "BTC",
                signal = AiSignal.BUY,
                confidence = BigDecimal("0.9"),
                riskUsd = BigDecimal("10"),
                stopLoss = BigDecimal("60000"),
                profitTarget = BigDecimal("70000"),
                leverage = 5,
            ),
        ),
    )

    private fun orchestrator(autoExecute: Boolean = true, riskEnabled: Boolean = true) =
        UpdateCycleOrchestrator(
            config = UpdateCycleOrchestrator.OrchestratorConfig(symbols = listOf("BTC"), autoExecute = autoExecute),
            useCases = UpdateCycleOrchestrator.UseCases(build = build, analyze = analyze, execute = execute),
            infrastructure = UpdateCycleOrchestrator.Infrastructure(
                market = market,
                trading = trading,
                killSwitchState = killSwitchState,
                riskGateProperties = RiskGateProperties(enabled = riskEnabled),
                meterRegistry = meterRegistry,
                tradingMetrics = tradingMetrics,
                schemaValidator = schemaValidator,
                confidenceCalibrator = confidenceCalibrator,
                tracer = tracer,
                clock = clock,
                tradeJournal = tradeJournal,
            ),
        )

    private fun stubPipelineUpToExecute() {
        coEvery { build.execute(any(), any(), any()) } returns PromptResult("PROMPT-TEXT", marketState)
        coEvery { analyze.execute(any()) } returns
            AiAnalysisResponse("test", "test", "{\"BTC\":{}}", 0L, true, null)
        every { schemaValidator.validateAndParse(any()) } returns
            AiSchemaValidator.ValidationResult.Valid(decisions)
        every { confidenceCalibrator.shouldAccept(any()) } returns
            ConfidenceCalibrator.CalibrationResult.Accepted
        every { killSwitchState.snapshot() } returns KillSwitchSnapshot.disabled()
    }

    @Test
    fun `empty market data skips the AI call entirely (no analyze)`() = runBlocking {
        // Realistic failure shape: every per-symbol fetch timed out, so the snapshot is a NON-empty map of
        // zero-price empty placeholders (what OkxExchangeAdapter.emptyCurrencyData produces). The cycle must
        // skip BEFORE the AI call (no wasted spend, no misleading "{}" schema rejection), reported as a skip.
        val zero = BigDecimal.ZERO
        val allFailed = listOf("BTC", "ETH").associateWith {
            CurrencyMarketData(symbol = it, currentPrice = zero, currentEma20 = zero, currentMacd = zero, currentRsi7 = zero)
        }
        coEvery { build.execute(any(), any(), any()) } returns
            PromptResult("PROMPT-TEXT", marketState.copy(currencies = allFailed))

        val result = orchestrator(autoExecute = true, riskEnabled = true).runOnce()

        assertEquals("Skipped (no market data)", result.message)
        coVerify(exactly = 0) { analyze.execute(any()) }
    }

    @Test
    fun `pnl read failure with risk enabled fails the cycle and skips execution (S1)`() = runBlocking {
        stubPipelineUpToExecute()
        coEvery { trading.getTodaysRealizedPnlUsd(any()) } returns
            TradeResult.Failure.ApiError("BILLS_READ_FAILED", "bills unavailable")

        val result = orchestrator(autoExecute = true, riskEnabled = true).runOnce()

        assertFalse(result.success, "cycle must fail closed when realized PnL is unreadable")
        assertEquals(0, result.positionsPlaced)
        coVerify(exactly = 0) { execute.execute(any(), any(), any()) }
    }

    @Test
    fun `pnl read failure with risk disabled still executes (PnL irrelevant)`() = runBlocking {
        stubPipelineUpToExecute()
        coEvery { trading.getTodaysRealizedPnlUsd(any()) } returns
            TradeResult.Failure.ApiError("BILLS_READ_FAILED", "bills unavailable")
        coEvery { execute.execute(any(), any(), any()) } returns emptyList()

        val result = orchestrator(autoExecute = true, riskEnabled = false).runOnce()

        assertTrue(result.success, "with risk gating disabled, a PnL read failure must not block trading")
        coVerify { execute.execute(any(), any(), any()) }
    }

    @Test
    fun `auto-execute disabled places zero without reaching execution`() = runBlocking {
        stubPipelineUpToExecute()

        val result = orchestrator(autoExecute = false, riskEnabled = true).runOnce()

        assertTrue(result.success)
        assertEquals(0, result.positionsPlaced)
        coVerify(exactly = 0) { execute.execute(any(), any(), any()) }
    }

    // =========================================================================
    // S5 — dedup hash advances only on a fully successful cycle
    // =========================================================================

    @Test
    fun `failed cycle does not advance dedup hash, so an identical prompt re-runs (S5)`() = runBlocking {
        coEvery { build.execute(any(), any(), any()) } returns PromptResult("PROMPT-TEXT", marketState)
        // Analysis fails downstream of the prompt-changed check.
        coEvery { analyze.execute(any()) } returns
            AiAnalysisResponse("test", "test", "", 0L, false, "boom")

        val orch = orchestrator(autoExecute = true, riskEnabled = true)
        val first = orch.runOnce()
        val second = orch.runOnce()

        assertFalse(first.success)
        assertFalse(second.success)
        // If the hash had been advanced on the first (failed) run, the second would skip analysis.
        coVerify(exactly = 2) { analyze.execute(any()) }
    }

    @Test
    fun `successful cycle advances dedup hash, so an identical prompt is skipped next time (S5)`() = runBlocking {
        stubPipelineUpToExecute()
        coEvery { trading.getTodaysRealizedPnlUsd(any()) } returns TradeResult.Success(BigDecimal.ZERO)
        coEvery { execute.execute(any(), any(), any()) } returns emptyList()

        val orch = orchestrator(autoExecute = true, riskEnabled = true)
        val first = orch.runOnce()
        val second = orch.runOnce()

        assertTrue(first.success)
        assertTrue(second.success)
        assertTrue(second.message.contains("Skipped"), "identical prompt should be skipped after a successful cycle")
        coVerify(exactly = 1) { analyze.execute(any()) } // second run skipped before analysis
    }

    // =========================================================================
    // Trade journal — PnL snapshot hook
    // =========================================================================

    @Test
    fun `auto-execute cycle records a PnL snapshot for the cycle`() = runBlocking {
        stubPipelineUpToExecute()
        coEvery { trading.getTodaysRealizedPnlUsd(any()) } returns TradeResult.Success(BigDecimal.ZERO)
        coEvery { execute.execute(any(), any(), any()) } returns emptyList()

        val result = orchestrator(autoExecute = true, riskEnabled = true).runOnce()

        assertTrue(result.success)
        verify(exactly = 1) {
            tradeJournal.recordPnlSnapshot(
                match<JournaledPnlSnapshot> { it.cycle == marketState.invocationCount && it.openPositionsCount == 0 }
            )
        }
    }
}

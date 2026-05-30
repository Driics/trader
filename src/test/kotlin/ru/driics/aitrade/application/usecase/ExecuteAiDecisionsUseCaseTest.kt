package ru.driics.aitrade.application.usecase

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.risk.KillSwitchSnapshot
import ru.driics.aitrade.application.risk.RiskContext
import ru.driics.aitrade.application.risk.RiskGate
import ru.driics.aitrade.config.RiskGateProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.ports.TradingPort
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

    private fun useCase() = ExecuteAiDecisionsUseCase(
        trading = trading,
        tradingProperties = props,
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
}

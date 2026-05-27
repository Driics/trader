package ru.driics.aitrade.application.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.RiskGateProperties
import java.math.BigDecimal
import java.time.Instant

class RiskGateTest {

    private val props = RiskGateProperties(
        enabled = true,
        maxDailyLossUsd = BigDecimal("50"),
        maxConcurrentPositions = 5,
    )

    private val killState = mockk<KillSwitchState>(relaxed = true)
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry =
        io.micrometer.core.instrument.simple.SimpleMeterRegistry()

    private fun ctx(
        openPositions: Int = 0,
        pnl: BigDecimal = BigDecimal.ZERO,
        kill: KillSwitchSnapshot = KillSwitchSnapshot.disabled(),
    ) = RiskContext(openPositions, pnl, kill)

    @Test
    fun `clean state returns Allow`() {
        val gate = RiskGate(props, killState, meterRegistry)
        assertEquals(RiskDecision.Allow, gate.evaluate(ctx()))
    }

    @Test
    fun `manual kill blocks with MANUAL_KILL source`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val manual = KillSwitchSnapshot(true, "ops", Instant.EPOCH, KillSwitchSnapshot.Source.MANUAL)
        val decision = gate.evaluate(ctx(kill = manual))
        assertTrue(decision is RiskDecision.Block)
        assertEquals(RiskDecision.Source.MANUAL_KILL, (decision as RiskDecision.Block).source)
        assertTrue(decision.reason.contains("ops"))
    }

    @Test
    fun `auto-tripped kill blocks with DAILY_LOSS_CAP source`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val auto = KillSwitchSnapshot(true, "loss", Instant.EPOCH, KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
        val decision = gate.evaluate(ctx(kill = auto))
        assertEquals(RiskDecision.Source.DAILY_LOSS_CAP, (decision as RiskDecision.Block).source)
    }

    @Test
    fun `pnl at minus cap trips kill-switch and blocks with DAILY_LOSS_CAP`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(pnl = BigDecimal("-50")))
        assertTrue(decision is RiskDecision.Block)
        assertEquals(RiskDecision.Source.DAILY_LOSS_CAP, (decision as RiskDecision.Block).source)
        verify { killState.trip(any(), KillSwitchSnapshot.Source.AUTO_DAILY_LOSS) }
        assertEquals(
            1.0,
            meterRegistry.find("risk.killswitch.tripped").tag("source", "AUTO_DAILY_LOSS").counter()?.count(),
        )
    }

    @Test
    fun `pnl below minus cap also trips`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(pnl = BigDecimal("-100")))
        assertEquals(RiskDecision.Source.DAILY_LOSS_CAP, (decision as RiskDecision.Block).source)
    }

    @Test
    fun `pnl above minus cap does not trip`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(pnl = BigDecimal("-49.99")))
        assertEquals(RiskDecision.Allow, decision)
        verify(exactly = 0) { killState.trip(any(), any()) }
    }

    @Test
    fun `open positions at cap blocks with POSITION_COUNT_CAP`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(openPositions = 5))
        assertEquals(RiskDecision.Source.POSITION_COUNT_CAP, (decision as RiskDecision.Block).source)
    }

    @Test
    fun `open positions below cap does not block`() {
        val gate = RiskGate(props, killState, meterRegistry)
        assertEquals(RiskDecision.Allow, gate.evaluate(ctx(openPositions = 4)))
    }

    @Test
    fun `disabled gate always returns Allow even with breached caps`() {
        val gate = RiskGate(props.copy(enabled = false), killState, meterRegistry)
        val decision = gate.evaluate(ctx(openPositions = 10, pnl = BigDecimal("-1000")))
        assertEquals(RiskDecision.Allow, decision)
        verify(exactly = 0) { killState.trip(any(), any()) }
    }

    @Test
    fun `kill snapshot precedence over loss cap and position cap`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val manual = KillSwitchSnapshot(true, "ops", Instant.EPOCH, KillSwitchSnapshot.Source.MANUAL)
        val decision = gate.evaluate(ctx(openPositions = 99, pnl = BigDecimal("-9999"), kill = manual))
        assertEquals(RiskDecision.Source.MANUAL_KILL, (decision as RiskDecision.Block).source)
    }
}

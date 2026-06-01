package ru.driics.aitrade.application.risk

import ru.driics.aitrade.config.RiskGateProperties
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot
import ru.driics.aitrade.domain.risk.RiskContext
import ru.driics.aitrade.domain.risk.RiskDecision

/**
 * Portfolio-wide risk evaluation. Stateless except for the side effect of
 * tripping [KillSwitchState] when the daily-loss cap is breached.
 *
 * Evaluation order: kill-switch -> daily loss -> position count.
 * If `props.enabled` is false, always returns [RiskDecision.Allow] and skips
 * the auto-trip side effect.
 */
class RiskGate(
    private val props: RiskGateProperties,
    private val killSwitch: KillSwitchState,
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry,
) {
    fun evaluate(ctx: RiskContext): RiskDecision {
        if (!props.enabled) return RiskDecision.Allow

        if (ctx.killSwitch.enabled) {
            val source = when (ctx.killSwitch.source) {
                KillSwitchSnapshot.Source.MANUAL -> RiskDecision.Source.MANUAL_KILL
                KillSwitchSnapshot.Source.AUTO_DAILY_LOSS -> RiskDecision.Source.DAILY_LOSS_CAP
                null -> RiskDecision.Source.MANUAL_KILL
            }
            return RiskDecision.Block(
                reason = ctx.killSwitch.reason ?: "kill-switch enabled",
                source = source,
            )
        }

        val lossThreshold = props.maxDailyLossUsd.negate()
        if (ctx.todaysRealizedPnlUsd <= lossThreshold) {
            val reason = "daily realized PnL ${ctx.todaysRealizedPnlUsd} <= cap $lossThreshold"
            killSwitch.trip(reason, KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
            meterRegistry.counter("risk.killswitch.tripped", "source", "AUTO_DAILY_LOSS").increment()
            return RiskDecision.Block(reason = reason, source = RiskDecision.Source.DAILY_LOSS_CAP)
        }

        if (ctx.openPositionsCount >= props.maxConcurrentPositions) {
            return RiskDecision.Block(
                reason = "open positions ${ctx.openPositionsCount} >= cap ${props.maxConcurrentPositions}",
                source = RiskDecision.Source.POSITION_COUNT_CAP,
            )
        }

        return RiskDecision.Allow
    }
}

package ru.driics.aitrade.domain.risk

import java.math.BigDecimal

/**
 * Snapshot of portfolio state evaluated once per orchestrator cycle and threaded into
 * ExecuteAiDecisionsUseCase. When the realized-PnL read fails the orchestrator fails CLOSED and
 * skips the cycle (S1), so this context is only built from a successful read.
 */
data class RiskContext(
    val openPositionsCount: Int,
    val todaysRealizedPnlUsd: BigDecimal,
    val killSwitch: KillSwitchSnapshot,
)

sealed class RiskDecision {
    data object Allow : RiskDecision()
    data class Block(val reason: String, val source: Source) : RiskDecision()

    enum class Source { MANUAL_KILL, DAILY_LOSS_CAP, POSITION_COUNT_CAP }
}

package ru.driics.aitrade.application.risk

import java.math.BigDecimal

/**
 * Snapshot of portfolio state evaluated once per orchestrator cycle and threaded
 * into ExecuteAiDecisionsUseCase. PnL of ZERO is used when the OKX read fails
 * (fail-open behaviour — see X2 design spec).
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

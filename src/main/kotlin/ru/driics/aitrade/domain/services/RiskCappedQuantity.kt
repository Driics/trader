package ru.driics.aitrade.domain.services

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Per-trade position sizing with a hard risk ceiling — pure + pinned because it picks the coin quantity
 * that sizes a real order. The SINGLE source of truth shared by the live path
 * ([ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase]) and the offline backtest
 * ([ru.driics.aitrade.domain.backtest.engine.sizeEntry]), so a backtest sizes positions exactly as live
 * would — no drift between "what the backtest shows" and "what the bot would actually trade".
 *
 * Makes `risk_usd` authoritative: the budget is `min(requestedRiskUsd, maxRiskPerTradePct * equityUsd)`,
 * the quantity is `budget / |entry - stop|`, and the model's own [modelQuantity] is advisory — honoured
 * only when it is MORE conservative (smaller) than the budget.
 *
 * Returns 0 (the caller skips/rejects) whenever the cap cannot be enforced — no stop, zero/negative
 * equity, or a non-positive budget — so an inflated model quantity is never traded uncapped.
 * [maxRiskPerTradePct] <= 0 disables the cap and restores legacy behaviour (model quantity wins; size
 * from [requestedRiskUsd] otherwise).
 */
internal fun resolveRiskCappedQuantity(
    modelQuantity: BigDecimal?,
    requestedRiskUsd: BigDecimal?,
    equityUsd: BigDecimal,
    maxRiskPerTradePct: BigDecimal,
    entryPrice: BigDecimal,
    stopLoss: BigDecimal?,
): BigDecimal {
    fun quantityForRisk(riskUsd: BigDecimal?): BigDecimal {
        if (riskUsd == null || riskUsd.signum() <= 0 || stopLoss == null || stopLoss.signum() <= 0) {
            return BigDecimal.ZERO
        }
        val riskPerUnit = (entryPrice - stopLoss).abs()
        return if (riskPerUnit.signum() > 0) riskUsd.divide(riskPerUnit, 8, RoundingMode.HALF_UP)
        else BigDecimal.ZERO
    }

    // Cap disabled -> legacy: the model's quantity is authoritative, risk-sizing only as a fallback.
    if (maxRiskPerTradePct.signum() <= 0) {
        return modelQuantity ?: quantityForRisk(requestedRiskUsd)
    }

    val maxRiskUsd = (equityUsd * maxRiskPerTradePct).coerceAtLeast(BigDecimal.ZERO)
    // Budget is the model's requested risk capped at the equity-based ceiling; the ceiling alone when the
    // model gave no (positive) risk_usd.
    val budget = requestedRiskUsd?.takeIf { it.signum() > 0 }?.min(maxRiskUsd) ?: maxRiskUsd
    val cappedQty = quantityForRisk(budget)
    if (cappedQty.signum() <= 0) return BigDecimal.ZERO
    // Model quantity is advisory: keep it only when it risks LESS than the budget.
    return modelQuantity?.min(cappedQty) ?: cappedQty
}

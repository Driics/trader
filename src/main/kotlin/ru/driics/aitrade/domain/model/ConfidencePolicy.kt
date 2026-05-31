package ru.driics.aitrade.domain.model

import java.math.BigDecimal

/**
 * Single source of truth for gating an AI confidence score against a threshold. The shared rule that
 * matters is the null-default: an ABSENT confidence counts as ZERO, so a missing score can never slip past
 * a positive gate. Applied at more than one stage (ConfidenceCalibrator pre-execution, and again in
 * ExecuteAiDecisionsUseCase.buildPlan as defense in depth) — both must agree, hence one definition.
 */
object ConfidencePolicy {
    fun meetsThreshold(confidence: BigDecimal?, minConfidence: BigDecimal): Boolean =
        (confidence ?: BigDecimal.ZERO) >= minConfidence
}

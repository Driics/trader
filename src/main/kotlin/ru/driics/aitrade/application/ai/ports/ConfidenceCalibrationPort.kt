package ru.driics.aitrade.application.ai.ports

import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.domain.model.AiTradeSignalArgs

/**
 * Port for confidence calibration and cooldown management.
 */
interface ConfidenceCalibrationPort {
    fun shouldAccept(signal: AiTradeSignalArgs): ConfidenceCalibrator.CalibrationResult
    fun recordTrade(symbol: String)
    fun getRemainingCooldownSeconds(symbol: String): Long
}


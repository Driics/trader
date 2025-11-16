package ru.driics.aitrade.application.ai

import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.types.Symbol
import java.math.BigDecimal
import java.math.BigDecimal.ONE
import java.math.BigDecimal.ZERO
import java.math.RoundingMode

/**
 * Sanitizes and normalizes AI signals before execution.
 * Ensures consistent format and prevents NPE/NumberFormat exceptions.
 */
class SignalNormalizer {
    companion object {
        private val log = logger<SignalNormalizer>()
        
        // Default scale for monetary values
        private const val DEFAULT_SCALE = 8
        private const val CONFIDENCE_SCALE = 4
        private const val MAX_LEVERAGE = 125
    }

    /**
     * Normalizes AI signal arguments.
     * Returns normalized signal or null if normalization fails.
     */
    fun normalize(signal: AiTradeSignalArgs): NormalizedSignal? {
        return try {
            // 1. Normalize symbol/coin
            val normalizedCoin = normalizeSymbol(signal.coin)
                ?: return null

            // 2. Normalize signal enum
            val normalizedSignal = normalizeSignal(signal.signal)
                ?: return null

            // 3. Normalize quantities and prices
            val normalizedQuantity = normalizeBigDecimal(signal.quantity, "quantity")
            val normalizedProfitTarget = normalizeBigDecimal(signal.profitTarget, "profit_target")
            val normalizedStopLoss = normalizeBigDecimal(signal.stopLoss, "stop_loss")
            val normalizedRiskUsd = normalizeBigDecimal(signal.riskUsd, "risk_usd")
            val normalizedConfidence = normalizeConfidence(signal.confidence)

            // 4. Normalize leverage
            val normalizedLeverage = normalizeLeverage(signal.leverage)

            NormalizedSignal(
                coin = normalizedCoin,
                signal = normalizedSignal,
                quantity = normalizedQuantity,
                profitTarget = normalizedProfitTarget,
                stopLoss = normalizedStopLoss,
                leverage = normalizedLeverage,
                confidence = normalizedConfidence,
                riskUsd = normalizedRiskUsd,
                invalidationCondition = signal.invalidationCondition?.trim()?.takeIf { it.isNotBlank() },
                justification = signal.justification?.trim()?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            log.error(e) { "Failed to normalize signal for ${signal.coin}" }
            null
        }
    }

    private fun normalizeSymbol(coin: String?): String? {
        if (coin.isNullOrBlank()) return null
        return try {
            Symbol.from(coin.trim()).value
        } catch (e: Exception) {
            log.warn { "Invalid symbol: $coin" }
            null
        }
    }

    private fun normalizeSignal(signal: AiSignal?): AiSignal? = signal

    private fun normalizeBigDecimal(value: BigDecimal?, fieldName: String): BigDecimal? {
        if (value == null) return null
        
        return try {
            when {
                value.isNegative() -> {
                    log.warn { "Negative value for $fieldName: $value, setting to null" }
                    null
                }
                else -> value.setScale(DEFAULT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to normalize $fieldName: $value" }
            null
        }
    }

    private fun normalizeConfidence(confidence: BigDecimal?): BigDecimal? {
        if (confidence == null) return null
        
        return try {
            when {
                confidence < ZERO -> {
                    log.warn { "Negative confidence: $confidence, setting to 0" }
                    ZERO
                }
                confidence > ONE -> {
                    log.warn { "Confidence > 1: $confidence, capping to 1" }
                    ONE
                }
                else -> confidence.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to normalize confidence: $confidence" }
            null
        }
    }

    private fun normalizeLeverage(leverage: Int?): Int? {
        if (leverage == null) return null
        
        return when {
            leverage < 1 -> {
                log.warn { "Leverage < 1: $leverage, setting to null" }
                null
            }
            leverage > MAX_LEVERAGE -> {
                log.warn { "Leverage > $MAX_LEVERAGE: $leverage, capping to $MAX_LEVERAGE" }
                MAX_LEVERAGE
            }
            else -> leverage
        }
    }

    data class NormalizedSignal(
        val coin: String,
        val signal: AiSignal,
        val quantity: BigDecimal?,
        val profitTarget: BigDecimal?,
        val stopLoss: BigDecimal?,
        val leverage: Int?,
        val confidence: BigDecimal?,
        val riskUsd: BigDecimal?,
        val invalidationCondition: String?,
        val justification: String?
    ) {
        fun toAiTradeSignalArgs(): AiTradeSignalArgs {
            return AiTradeSignalArgs(
                coin = coin,
                signal = signal,
                quantity = quantity,
                profitTarget = profitTarget,
                stopLoss = stopLoss,
                leverage = leverage,
                confidence = confidence,
                riskUsd = riskUsd,
                invalidationCondition = invalidationCondition,
                justification = justification
            )
        }
    }
}


package ru.driics.aitrade.application.ai

import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.InstrumentDefaults
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import ru.driics.aitrade.domain.types.OrderSide
import ru.driics.aitrade.domain.util.isPositive
import ru.driics.aitrade.domain.util.isZeroOrNegative
import ru.driics.aitrade.domain.util.quantize
import ru.driics.aitrade.domain.util.quantizeToLot
import java.math.BigDecimal

/**
 * Guardrails for AI actions - hard validation before execution.
 * All violations result in SKIPPED status with clear reason, not errors.
 * 
 * This is a pure domain service with no side effects.
 */
class ActionGuard(
    private val tradingProperties: TradingProperties
) {
    companion object {
        private val log = logger<ActionGuard>()
        
        private const val MIN_TP_SL_TICKS = 5
    }

    /**
     * Validates AI plan before execution.
     * Returns ValidationResult.Valid if valid, or ValidationResult.Rejected with reason if invalid.
     */
    fun validate(
        plan: AiTradeSignalArgs,
        instrumentInfo: OkxInstrumentInfo,
        lastPrice: BigDecimal,
        instrumentMaxLeverage: Int? = null
    ): ValidationResult {
        // 1. Normalize and validate signal
        val normalizedSignal = normalizeSignal(plan.signal)
            ?: return ValidationResult.Rejected("Invalid signal: ${plan.signal}")

        // 2. Validate leverage
        val requestedLeverage = plan.leverage ?: tradingProperties.minLeverage
        val maxLeverage = minOf(
            instrumentMaxLeverage ?: Int.MAX_VALUE,
            tradingProperties.maxLeverage
        )
        
        if (requestedLeverage !in 1..maxLeverage) {
            return ValidationResult.Rejected(
                "Leverage $requestedLeverage out of range [1, $maxLeverage]"
            )
        }

        // 3. Extract and validate instrument parameters
        val tickSz = extractInstrumentValue(instrumentInfo.tickSz, InstrumentDefaults.TICK_SIZE)
        val lotSz = extractInstrumentValue(instrumentInfo.lotSz, InstrumentDefaults.LOT_SIZE)
        
        // 4. Quantize and validate prices
        val quantizedEntry = lastPrice.quantize(tickSz)
        val quantizedTp = plan.profitTarget?.quantize(tickSz)
        val quantizedSl = plan.stopLoss?.quantize(tickSz)
        
        // 5. Validate TP/SL
        validateTpSl(quantizedTp, quantizedEntry, tickSz, normalizedSignal, isTp = true)
            ?.let { return ValidationResult.Rejected(it) }
        
        validateTpSl(quantizedSl, quantizedEntry, tickSz, normalizedSignal, isTp = false)
            ?.let { return ValidationResult.Rejected(it) }
        
        // 6. Validate and quantize quantity
        plan.quantity?.takeIf { it.isZeroOrNegative() }
            ?.let { return ValidationResult.Rejected("Quantity must be positive, got: $it") }
        
        val quantizedQuantity = plan.quantity?.quantizeToLot(lotSz)
        quantizedQuantity?.takeIf { it < lotSz }
            ?.let { return ValidationResult.Rejected("Quantity $it below minimum lot size $lotSz") }

        // 7. Validate risk limits
        plan.riskUsd?.takeIf { it.isZeroOrNegative() }
            ?.let { return ValidationResult.Rejected("Risk USD must be positive, got: $it") }

        return ValidationResult.Valid(
            normalizedSignal = normalizedSignal,
            leverage = requestedLeverage.coerceIn(1, maxLeverage),
            quantizedEntry = quantizedEntry,
            quantizedTp = quantizedTp,
            quantizedSl = quantizedSl,
            quantizedQuantity = quantizedQuantity
        )
    }

    private fun extractInstrumentValue(value: String?, default: BigDecimal): BigDecimal =
        value?.toBigDecimalOrNull()?.takeIf { it.isPositive() } ?: default

    private fun normalizeSignal(signal: AiSignal): OrderSide? = OrderSide.fromAiSignal(signal)

    private fun validateTpSl(
        price: BigDecimal?,
        lastPrice: BigDecimal,
        tickSz: BigDecimal,
        side: OrderSide,
        isTp: Boolean
    ): String? {
        if (price == null) return null // Optional TP/SL

        val label = if (isTp) "TP" else "SL"

        if (price.isZeroOrNegative()) {
            return "$label must be positive, got: $price"
        }

        val minDistance = tickSz * BigDecimal.valueOf(MIN_TP_SL_TICKS.toLong())
        val distance = (price - lastPrice).abs()

        if (distance < minDistance) {
            return "$label $price too close to entry $lastPrice (min distance: $minDistance)"
        }

        // Validate direction: TP should be favorable, SL should be unfavorable.
        val isFavorable = when (side) {
            OrderSide.BUY -> price > lastPrice
            OrderSide.SELL -> price < lastPrice
        }

        if (isTp && !isFavorable) {
            return "$label $price is not favorable for $side at entry $lastPrice"
        }
        
        if (!isTp && isFavorable) {
            return "$label $price is not unfavorable for $side at entry $lastPrice"
        }

        return null
    }

    sealed class ValidationResult {
        data class Valid(
            val normalizedSignal: OrderSide,
            val leverage: Int,
            val quantizedEntry: BigDecimal,
            val quantizedTp: BigDecimal?,
            val quantizedSl: BigDecimal?,
            val quantizedQuantity: BigDecimal?
        ) : ValidationResult()

        data class Rejected(val reason: String) : ValidationResult()
    }
}


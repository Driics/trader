package ru.driics.aitrade.application.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import ru.driics.aitrade.domain.types.InstrumentId
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Guardrails for AI actions - hard validation before execution.
 * All violations result in SKIPPED status with clear reason, not errors.
 */
class ActionGuard(
    private val tradingProperties: TradingProperties
) {
    companion object {
        private val log = KotlinLogging.logger {}
        
        // Minimum distance from last price for TP/SL (in ticks)
        private const val MIN_TP_SL_TICKS = 5
    }

    /**
     * Validates AI plan before execution.
     * Returns null if valid, or rejection reason if invalid.
     */
    fun validate(
        plan: AiTradeSignalArgs,
        instrumentInfo: OkxInstrumentInfo,
        lastPrice: BigDecimal,
        instrumentMaxLeverage: Int? = null
    ): ValidationResult {
        // 1. Normalize and validate signal
        val normalizedSignal = normalizeSignal(plan.signal)
        if (normalizedSignal == null) {
            return ValidationResult.Rejected("Invalid signal: ${plan.signal}")
        }

        // 2. Validate leverage
        val requestedLeverage = plan.leverage ?: tradingProperties.minLeverage
        val maxLeverage = minOf(
            instrumentMaxLeverage ?: Int.MAX_VALUE,
            tradingProperties.maxLeverage
        )
        
        if (requestedLeverage < 1 || requestedLeverage > maxLeverage) {
            return ValidationResult.Rejected(
                "Leverage $requestedLeverage out of range [1, $maxLeverage]"
            )
        }

        // 3. Quantize and validate prices
        val tickSz = instrumentInfo.tickSz?.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?: BigDecimal("0.01")
        
        val entryPx = plan.quantity?.let { lastPrice } ?: lastPrice
        val quantizedEntry = quantizePrice(entryPx, tickSz)
        
        // 4. Validate TP/SL
        val tp = plan.profitTarget?.let { quantizePrice(it, tickSz) }
        val sl = plan.stopLoss?.let { quantizePrice(it, tickSz) }
        
        val tpValidation = validateTpSl(
            price = tp,
            lastPrice = quantizedEntry,
            tickSz = tickSz,
            side = normalizedSignal,
            isTp = true
        )
        if (tpValidation != null) {
            return ValidationResult.Rejected(tpValidation)
        }
        
        val slValidation = validateTpSl(
            price = sl,
            lastPrice = quantizedEntry,
            tickSz = tickSz,
            side = normalizedSignal,
            isTp = false
        )
        if (slValidation != null) {
            return ValidationResult.Rejected(slValidation)
        }

        // 5. Quantize quantity
        val lotSz = instrumentInfo.lotSz?.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?: BigDecimal.ONE
        
        val quantity = plan.quantity
        if (quantity != null && quantity <= BigDecimal.ZERO) {
            return ValidationResult.Rejected("Quantity must be positive, got: $quantity")
        }
        
        val quantizedQuantity = quantity?.let { quantizeQuantity(it, lotSz) }
        if (quantizedQuantity != null && quantizedQuantity < lotSz) {
            return ValidationResult.Rejected(
                "Quantity $quantizedQuantity below minimum lot size $lotSz"
            )
        }

        // 6. Validate risk limits
        val riskUsd = plan.riskUsd
        if (riskUsd != null && riskUsd <= BigDecimal.ZERO) {
            return ValidationResult.Rejected("Risk USD must be positive, got: $riskUsd")
        }

        // 7. Validate position value (if quantity and price available)
        if (quantizedQuantity != null && quantizedEntry > BigDecimal.ZERO) {
            val positionValueUsd = quantizedQuantity.multiply(quantizedEntry)
            // Note: maxPositionValueUsd should be added to TradingProperties if needed
            // For now, we skip this check or use a reasonable default
        }

        return ValidationResult.Valid(
            normalizedSignal = normalizedSignal,
            leverage = requestedLeverage.coerceIn(1, maxLeverage),
            quantizedEntry = quantizedEntry,
            quantizedTp = tp,
            quantizedSl = sl,
            quantizedQuantity = quantizedQuantity
        )
    }

    private fun normalizeSignal(signal: AiSignal): String? = when (signal) {
        AiSignal.BUY -> "buy"
        AiSignal.SELL -> "sell"
        AiSignal.HOLD -> null // Hold should be filtered earlier
    }

    private fun quantizePrice(price: BigDecimal, tickSz: BigDecimal): BigDecimal {
        return price.divide(tickSz, 0, RoundingMode.HALF_UP)
            .multiply(tickSz)
            .setScale(tickSz.scale(), RoundingMode.HALF_UP)
    }

    private fun quantizeQuantity(quantity: BigDecimal, lotSz: BigDecimal): BigDecimal {
        return quantity.divide(lotSz, 0, RoundingMode.DOWN)
            .multiply(lotSz)
            .setScale(lotSz.scale(), RoundingMode.DOWN)
    }

    private fun validateTpSl(
        price: BigDecimal?,
        lastPrice: BigDecimal,
        tickSz: BigDecimal,
        side: String,
        isTp: Boolean
    ): String? {
        if (price == null) return null // Optional TP/SL
        
        if (price <= BigDecimal.ZERO) {
            return "${if (isTp) "TP" else "SL"} must be positive, got: $price"
        }

        val minDistance = tickSz.multiply(BigDecimal(MIN_TP_SL_TICKS))
        val distance = (price - lastPrice).abs()

        if (distance < minDistance) {
            return "${if (isTp) "TP" else "SL"} $price too close to entry $lastPrice " +
                    "(min distance: $minDistance)"
        }

        // Validate direction: TP should be favorable, SL should be unfavorable
        val isBuy = side == "buy"
        val isFavorable = if (isBuy) price > lastPrice else price < lastPrice
        
        if (isTp && !isFavorable) {
            return "TP $price is not favorable for $side at entry $lastPrice"
        }
        
        if (!isTp && isFavorable) {
            return "SL $price is not unfavorable for $side at entry $lastPrice"
        }

        return null
    }

    sealed class ValidationResult {
        data class Valid(
            val normalizedSignal: String,
            val leverage: Int,
            val quantizedEntry: BigDecimal,
            val quantizedTp: BigDecimal?,
            val quantizedSl: BigDecimal?,
            val quantizedQuantity: BigDecimal?
        ) : ValidationResult()

        data class Rejected(val reason: String) : ValidationResult()
    }
}


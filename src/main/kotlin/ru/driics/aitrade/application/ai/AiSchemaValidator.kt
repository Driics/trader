package ru.driics.aitrade.application.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeDecisionMap
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal

/**
 * Validates AI response JSON against schema and business rules.
 * Ensures no "dirty" responses go into execution.
 */
class AiSchemaValidator(
    private val objectMapper: ObjectMapper,
    private val validator: Validator
) {
    companion object {
        private val log = logger<AiSchemaValidator>()
    }

    /**
     * Validates and parses AI response JSON.
     * Returns ValidationResult with either valid parsed data or rejection reason.
     */
    fun validateAndParse(responseJson: String): ValidationResult {
        return try {
            // Step 1: Parse JSON
            val parsed: Map<String, Any?>
            try {
                parsed = objectMapper.readValue<Map<String, Any?>>(responseJson)
            } catch (e: Exception) {
                return ValidationResult.Rejected("Invalid JSON format: ${e.message}")
            }

            if (parsed.isEmpty()) {
                return ValidationResult.Rejected("Empty response")
            }

            // Step 2: Parse into domain model
            val decisions: AiTradeDecisionMap
            try {
                decisions = objectMapper.readValue<AiTradeDecisionMap>(responseJson)
            } catch (e: Exception) {
                return ValidationResult.Rejected("Failed to parse as AiTradeDecisionMap: ${e.message}")
            }

            // Step 3: Validate each decision
            val validatedDecisions = mutableMapOf<String, AiTradeEnvelope>()
            val rejected = mutableListOf<Pair<String, String>>()

            for ((symbol, envelope) in decisions) {
                val validation = validateSignalArgs(symbol, envelope.args)
                when (validation) {
                    is SignalValidationResult.Valid -> {
                        validatedDecisions[symbol] = envelope
                    }
                    is SignalValidationResult.Rejected -> {
                        rejected.add(symbol to validation.reason)
                        log.warn { "Rejected signal for $symbol: ${validation.reason}" }
                    }
                }
            }

            if (validatedDecisions.isEmpty()) {
                return ValidationResult.Rejected(
                    "All signals rejected: ${rejected.joinToString("; ") { "${it.first}: ${it.second}" }}"
                )
            }

            if (rejected.isNotEmpty()) {
                log.info { "Partially validated: ${validatedDecisions.size} valid, ${rejected.size} rejected" }
            }

            ValidationResult.Valid(validatedDecisions)
        } catch (e: Exception) {
            log.error(e) { "Unexpected error during schema validation" }
            ValidationResult.Rejected("Validation error: ${e.message}")
        }
    }

    private fun validateSignalArgs(symbol: String, args: AiTradeSignalArgs): SignalValidationResult {
        // 1. Validate using Bean Validation
        val violations: Set<ConstraintViolation<AiTradeSignalArgs>> = validator.validate(args)
        if (violations.isNotEmpty()) {
            val reasons = violations.joinToString("; ") { "${it.propertyPath}: ${it.message}" }
            return SignalValidationResult.Rejected("Bean validation failed: $reasons")
        }

        // 2. Validate coin matches symbol key
        if (args.coin.uppercase() != symbol.uppercase()) {
            return SignalValidationResult.Rejected("Coin mismatch: key=$symbol, coin=${args.coin}")
        }

        // 3. Validate signal enum
        if (args.signal !in AiSignal.values()) {
            return SignalValidationResult.Rejected("Invalid signal: ${args.signal}")
        }

        // 4. Validate confidence range [0, 1]
        if (args.confidence != null) {
            if (args.confidence < BigDecimal.ZERO || args.confidence > BigDecimal.ONE) {
                return SignalValidationResult.Rejected("Confidence out of range [0, 1]: ${args.confidence}")
            }
        }

        // 5. Validate leverage range [1, 125]
        if (args.leverage != null) {
            if (args.leverage < 1 || args.leverage > 125) {
                return SignalValidationResult.Rejected("Leverage out of range [1, 125]: ${args.leverage}")
            }
        }

        // 6. Validate prices are positive (if provided)
        if (args.profitTarget != null && args.profitTarget <= BigDecimal.ZERO) {
            return SignalValidationResult.Rejected("Profit target must be positive: ${args.profitTarget}")
        }

        if (args.stopLoss != null && args.stopLoss <= BigDecimal.ZERO) {
            return SignalValidationResult.Rejected("Stop loss must be positive: ${args.stopLoss}")
        }

        // 7. Validate quantity is positive (if provided)
        if (args.quantity != null && args.quantity <= BigDecimal.ZERO) {
            return SignalValidationResult.Rejected("Quantity must be positive: ${args.quantity}")
        }

        // 8. Validate riskUsd is positive (if provided)
        if (args.riskUsd != null && args.riskUsd <= BigDecimal.ZERO) {
            return SignalValidationResult.Rejected("Risk USD must be positive: ${args.riskUsd}")
        }

        // 9. For BUY/SELL signals, require either quantity or riskUsd
        if (args.signal != AiSignal.HOLD) {
            if (args.quantity == null && args.riskUsd == null) {
                return SignalValidationResult.Rejected("BUY/SELL signal requires quantity or riskUsd")
            }
        }

        return SignalValidationResult.Valid
    }

    sealed class ValidationResult {
        data class Valid(val decisions: AiTradeDecisionMap) : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }

    private sealed class SignalValidationResult {
        object Valid : SignalValidationResult()
        data class Rejected(val reason: String) : SignalValidationResult()
    }
}


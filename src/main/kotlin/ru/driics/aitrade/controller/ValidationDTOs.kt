package ru.driics.aitrade.controller

import jakarta.validation.constraints.*
import ru.driics.aitrade.domain.model.TradingLimits
import java.math.BigDecimal

/**
 * Validated request DTOs for TradingSystemController.
 */

/**
 * Request to manually trigger an update cycle.
 */
data class TriggerUpdateRequest(
    @field:NotNull(message = "Force flag is required")
    val force: Boolean = false,

    @field:Size(min = 1, max = 50, message = "Symbols list must contain 1-50 items")
    val symbols: List<@Pattern(regexp = "[A-Z0-9]+") String>? = null
)

/**
 * Request to set leverage for an instrument.
 */
data class SetLeverageRequest(
    @field:NotBlank(message = "Instrument ID is required")
    @field:Pattern(regexp = "[A-Z0-9]+-[A-Z]+-[A-Z]+", message = "Invalid instrument ID format")
    val instrumentId: String,

    @field:Min(value = 1, message = "Leverage must be at least 1")
    @field:Max(value = TradingLimits.MAX_LEVERAGE_LONG, message = "Leverage cannot exceed 125")
    val leverage: Int,

    @field:NotBlank(message = "Margin mode is required")
    @field:Pattern(regexp = "cross|isolated", flags = [Pattern.Flag.CASE_INSENSITIVE])
    val marginMode: String = "cross"
)

/**
 * Request to place a manual order.
 */
data class PlaceOrderRequest(
    @field:NotBlank(message = "Instrument ID is required")
    @field:Pattern(regexp = "[A-Z0-9]+-[A-Z]+-[A-Z]+", message = "Invalid instrument ID format")
    val instrumentId: String,

    @field:NotBlank(message = "Side is required")
    @field:Pattern(regexp = "buy|sell", flags = [Pattern.Flag.CASE_INSENSITIVE])
    val side: String,

    @field:NotNull(message = "Quantity is required")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be positive")
    val quantity: BigDecimal,

    @field:DecimalMin(value = "0.0", inclusive = false, message = "Take profit must be positive")
    val takeProfitPrice: BigDecimal? = null,

    @field:DecimalMin(value = "0.0", inclusive = false, message = "Stop loss must be positive")
    val stopLossPrice: BigDecimal? = null,

    @field:NotNull(message = "Tick size is required")
    @field:DecimalMin(value = "0.0", inclusive = false)
    val tickSize: BigDecimal,

    @field:NotBlank(message = "Client order ID is required")
    val clientOrderId: String,

    val tag: String? = null,

    @field:NotBlank(message = "Margin mode is required")
    @field:Pattern(regexp = "cross|isolated", flags = [Pattern.Flag.CASE_INSENSITIVE])
    val marginMode: String = "cross"
)

/**
 * Request to update trading configuration.
 */
data class UpdateConfigRequest(
    @field:Min(value = 100, message = "Max position value must be at least 100")
    val maxPositionValueUsd: BigDecimal? = null,

    @field:Min(value = 1, message = "Max concurrent symbols must be at least 1")
    @field:Max(value = 50, message = "Max concurrent symbols cannot exceed 50")
    val maxConcurrentSymbols: Int? = null,

    @field:DecimalMin(value = "0.0", message = "Reserve ratio cannot be negative")
    @field:DecimalMax(value = "1.0", message = "Reserve ratio cannot exceed 1.0")
    val reserveRatio: BigDecimal? = null
)
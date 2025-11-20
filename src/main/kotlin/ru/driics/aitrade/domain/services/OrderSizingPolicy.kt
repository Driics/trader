package ru.driics.aitrade.domain.services

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Domain service responsible for calculating position sizes based on risk,
 * available balance, and instrument specifications.
 */
class OrderSizingPolicy(
    private val takerFeePct: BigDecimal,
    private val marginBufferPct: BigDecimal,
    private val minLev: Int = 5,
    private val maxLev: Int = 40
) {
    private companion object {
        // Standard USD-pegged quote currencies
        val USD_QUOTES = setOf("USDT", "USD", "USDC", "USB")
        val ZERO = BigDecimal.ZERO
        val ONE = BigDecimal.ONE
    }

    data class SizingInput(
        val coinQty: BigDecimal,
        val entryPx: BigDecimal,
        val ctVal: BigDecimal,      // Contract Value (multiplier)
        val ctValCcy: String,       // Contract Value Currency (e.g., "BTC" or "USDT")
        val lotSz: BigDecimal,      // Minimum step size for contracts
        val minSz: BigDecimal,      // Minimum order size
        val leverage: Int,
        val availableUsd: BigDecimal
    )

    data class SizingResult(
        val requestedContracts: BigDecimal, // The theoretical amount needed
        val roundedContracts: BigDecimal,   // The actual amount we can order (stepped & capped)
        val perContractUsd: BigDecimal,     // Cost to open 1 contract (Margin + Fee + Buffer)
        val totalUsd: BigDecimal,           // Total cost of the order
        val leverage: Int
    )

    /**
     * Calculates the order size.
     * Returns null if the resulting size is invalid (<= 0) or unaffordable.
     */
    fun size(input: SizingInput): SizingResult? {
        if (input.ctVal <= ZERO || input.lotSz <= ZERO) return null

        val leverage = input.leverage.coerceIn(minLev, maxLev)
        val isUsdQuote = input.ctValCcy.uppercase() in USD_QUOTES

        // 1. Determine Value per Contract in USD
        // Linear/USDT-margined: ctVal is usually the multiplier (e.g. 1 or 0.01).
        // Inverse/Coin-margined: ctVal is in coin, so we multiply by price.
        val contractValueUsd = if (isUsdQuote) {
            input.ctVal
        } else {
            input.ctVal * input.entryPx
        }

        if (contractValueUsd <= ZERO) return null

        // 2. Calculate Raw Contract Count required to match input CoinQty
        val rawContracts = if (isUsdQuote) {
            // (Qty * Price) / ContractVal
            (input.coinQty * input.entryPx).divide(input.ctVal, 8, RoundingMode.HALF_UP)
        } else {
            // Qty / ContractVal
            input.coinQty.divide(input.ctVal, 8, RoundingMode.HALF_UP)
        }

        if (rawContracts <= ZERO) return null

        // 3. Round down to nearest Lot Size
        val idealContracts = rawContracts.quantizeDownToLot(input.lotSz, input.minSz)
            ?: return null

        // 4. Calculate Cost per 1 Contract (Initial Margin + Fees + Buffer)
        // Cost = Value * ( (1/Lev) + Fee% + Buffer% )
        val marginRatio = ONE.divide(BigDecimal(leverage), 8, RoundingMode.HALF_UP)
        val costPerContract = contractValueUsd * (marginRatio + takerFeePct + marginBufferPct)

        if (costPerContract <= ZERO) return null

        // 5. Check Affordability (Max contracts we can buy with available USD)
        val maxAffordableContracts = input.availableUsd.divide(costPerContract, 8, RoundingMode.FLOOR)
            .quantizeDownToLot(input.lotSz, input.minSz)
            ?: return null // Cannot afford even the minimum size

        // 6. Final Logic: Take the smaller of Ideal vs Affordable
        val finalContracts = minOf(idealContracts, maxAffordableContracts)
        val totalCost = finalContracts * costPerContract

        return SizingResult(
            requestedContracts = rawContracts.stripTrailingZeros(),
            roundedContracts = finalContracts.stripTrailingZeros(),
            perContractUsd = costPerContract,
            totalUsd = totalCost,
            leverage = leverage
        )
    }

    /**
     * Helper to round a number DOWN to the nearest lot step.
     * Returns null if the result is less than minSize.
     */
    private fun BigDecimal.quantizeDownToLot(lotSize: BigDecimal, minSize: BigDecimal): BigDecimal? {
        if (lotSize <= ZERO) return null

        // divideAndRemainder is slower; straightforward logic: floor(val / lot) * lot
        val steps = this.divide(lotSize, 0, RoundingMode.FLOOR)
        val quantized = steps * lotSize

        return if (quantized >= minSize && quantized > ZERO) {
            quantized
        } else {
            null
        }
    }
}
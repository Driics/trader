package ru.driics.aitrade.domain.services

import java.math.BigDecimal
import java.math.RoundingMode

class OrderSizingPolicy(
    private val takerFeePct: BigDecimal,
    private val marginBufferPct: BigDecimal,
    private val minLev: Int = 5,
    private val maxLev: Int = 40
) {
    data class SizingInput(
        val coinQty: BigDecimal,
        val entryPx: BigDecimal,
        val ctVal: BigDecimal,
        val ctValCcy: String,
        val lotSz: BigDecimal,
        val minSz: BigDecimal,
        val leverage: Int,
        val availableUsd: BigDecimal
    )
    data class SizingOutput(
        val requestedContracts: BigDecimal,
        val roundedContracts: BigDecimal,
        val perContractUsd: BigDecimal,
        val totalUsd: BigDecimal,
        val leverage: Int
    )

    fun size(input: SizingInput): SizingOutput? {
        val lev = input.leverage.coerceIn(minLev, maxLev)
        val vPerContract = if (input.ctValCcy.uppercase() in setOf("USDT","USD","USB")) input.ctVal else input.ctVal * input.entryPx
        if (vPerContract <= BigDecimal.ZERO) return null

        val rawContracts =
            if (input.ctValCcy.uppercase() in setOf("USDT","USD","USB")) input.coinQty * input.entryPx / input.ctVal
            else input.coinQty / input.ctVal
        if (rawContracts <= BigDecimal.ZERO) return null

        val rounded = round(rawContracts, input.lotSz, input.minSz) ?: return null
        val perContract = perContractCash(vPerContract, lev, takerFeePct, marginBufferPct)
        val maxAff = affordable(input.availableUsd, perContract, input.lotSz, input.minSz) ?: return null
        val finalContracts = if (rounded > maxAff) maxAff else rounded
        val total = finalContracts * perContract

        return SizingOutput(rawContracts.stripTrailingZeros(), finalContracts, perContract, total, lev)
    }

    private fun perContractCash(v: BigDecimal, lev: Int, fee: BigDecimal, buf: BigDecimal): BigDecimal =
        v * (BigDecimal.ONE.divide(BigDecimal(lev), 8, RoundingMode.HALF_UP) + fee + buf)

    private fun round(raw: BigDecimal, lot: BigDecimal, min: BigDecimal): BigDecimal? {
        val steps = raw.divide(lot, 0, RoundingMode.FLOOR)
        val floored = steps * lot
        return floored.takeIf { it >= min }?.stripTrailingZeros()
    }

    private fun affordable(av: BigDecimal, per: BigDecimal, lot: BigDecimal, min: BigDecimal): BigDecimal? {
        if (av <= BigDecimal.ZERO || per <= BigDecimal.ZERO) return null
        val steps = av.divide(per, 8, RoundingMode.FLOOR).divide(lot, 0, RoundingMode.FLOOR)
        val floored = steps * lot
        return floored.takeIf { it >= min }?.stripTrailingZeros()
    }
}
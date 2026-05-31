package ru.driics.aitrade.domain.backtest.engine

import ru.driics.aitrade.domain.services.OrderSizingPolicy
import ru.driics.aitrade.domain.strategy.StrategyDecision
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * USD-pegged quote currencies — must mirror the private set inside
 * [ru.driics.aitrade.domain.services.OrderSizingPolicy]. The contracts↔coin round-trip test guards this
 * duplication: if the two ever diverge, sizing in the backtest no longer matches production.
 */
internal val USD_QUOTES = setOf("USDT", "USD", "USDC", "USB")

/**
 * Inverse of the contract-count math in [OrderSizingPolicy.size]: given a rounded contract count, return
 * the actual COIN quantity the position represents. This is the single place the sim re-derives coin
 * quantity, so it mirrors the policy's `isUsdQuote` branch exactly:
 *  - USD-quote contract (ctValCcy in [USD_QUOTES]): `coinQty = contracts * ctVal / entryPx`
 *  - coin-quote contract (e.g. BTC-USDT-SWAP, ctValCcy="BTC"): `coinQty = contracts * ctVal`
 */
internal fun contractsToCoinQty(
    contracts: BigDecimal,
    ctVal: BigDecimal,
    ctValCcy: String,
    entryPx: BigDecimal,
): BigDecimal {
    val isUsdQuote = ctValCcy.uppercase() in USD_QUOTES
    return if (isUsdQuote) {
        if (entryPx.signum() == 0) BigDecimal.ZERO
        else contracts.multiply(ctVal).divide(entryPx, 8, RoundingMode.HALF_UP)
    } else {
        contracts.multiply(ctVal)
    }
}

/** Why an entry was not opened (or, when [coinQty] is non-null, that it was sized successfully). */
enum class EntryRejection { NO_STOP, UNAFFORDABLE }

/** Result of sizing an entry: either a positive [coinQty] with its [leverage], or a [rejection]. */
data class EntrySizing(
    val coinQty: BigDecimal?,
    val leverage: Int,
    val rejection: EntryRejection?,
)

/**
 * Sizes one entry at the FILL price (invariant: risk is normalized to the price we actually get, not the
 * close the strategy decided on). Flow:
 *  1. No stop → reject (risk would divide by an undefined distance — see design invariant 4).
 *  2. Intent coin qty: [StrategyDecision.quantity] if the strategy sized it explicitly, else
 *     `equity * riskPerTradePct / |fill - stop|`.
 *  3. Route through [OrderSizingPolicy.size] for affordability + lot rounding + leverage clamp; a null
 *     result (unaffordable / below minSz) → reject.
 *  4. Invert the rounded contracts back to a coin quantity via [contractsToCoinQty].
 */
internal fun sizeEntry(
    decision: StrategyDecision,
    fillPx: BigDecimal,
    equity: BigDecimal,
    availableUsd: BigDecimal,
    spec: InstrumentSpec,
    policy: OrderSizingPolicy,
    riskPerTradePct: BigDecimal,
    defaultLeverage: Int,
): EntrySizing {
    val stop = decision.stopLoss ?: return EntrySizing(null, 0, EntryRejection.NO_STOP)

    val intentCoinQty = decision.quantity ?: run {
        val stopDistance = (fillPx - stop).abs()
        if (stopDistance.signum() <= 0) return EntrySizing(null, 0, EntryRejection.NO_STOP)
        (equity * riskPerTradePct).divide(stopDistance, 8, RoundingMode.HALF_UP)
    }
    if (intentCoinQty.signum() <= 0) return EntrySizing(null, 0, EntryRejection.UNAFFORDABLE)

    val sized = policy.size(
        OrderSizingPolicy.SizingInput(
            coinQty = intentCoinQty,
            entryPx = fillPx,
            ctVal = spec.ctVal,
            ctValCcy = spec.ctValCcy,
            lotSz = spec.lotSz,
            minSz = spec.minSz,
            leverage = decision.leverage ?: defaultLeverage,
            availableUsd = availableUsd,
        ),
    ) ?: return EntrySizing(null, 0, EntryRejection.UNAFFORDABLE)

    val coinQty = contractsToCoinQty(sized.roundedContracts, spec.ctVal, spec.ctValCcy, fillPx)
    if (coinQty.signum() <= 0) return EntrySizing(null, 0, EntryRejection.UNAFFORDABLE)

    return EntrySizing(coinQty, sized.leverage, null)
}

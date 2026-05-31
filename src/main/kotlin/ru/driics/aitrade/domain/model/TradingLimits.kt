package ru.driics.aitrade.domain.model

/**
 * Exchange-wide hard limits, centralised so every validation layer agrees on the same number — a single
 * source of truth. (Previously the `125` ceiling was hardcoded in both SignalNormalizer and
 * AiSchemaValidator, which could silently diverge.)
 */
object TradingLimits {
    /** OKX absolute maximum leverage; the ceiling every leverage check must respect. */
    const val MAX_LEVERAGE = 125

    /**
     * The same ceiling as a Long, for Bean Validation `@field:Max` (whose `value` must be a Long
     * constant). Kept beside [MAX_LEVERAGE] so the number still lives in exactly one file — keep equal.
     */
    const val MAX_LEVERAGE_LONG = 125L
}

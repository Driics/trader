package ru.driics.aitrade.domain.types

/**
 * Type-safe order side representation.
 * Prevents mixing buy/sell strings throughout the codebase.
 */
enum class OrderSide(val value: String) {
    BUY("buy"),
    SELL("sell");

    companion object {
        fun fromString(value: String): OrderSide? = when (value.lowercase()) {
            "buy" -> BUY
            "sell" -> SELL
            else -> null
        }

        fun fromAiSignal(signal: ru.driics.aitrade.domain.model.AiSignal): OrderSide? = when (signal) {
            ru.driics.aitrade.domain.model.AiSignal.BUY -> BUY
            ru.driics.aitrade.domain.model.AiSignal.SELL -> SELL
            ru.driics.aitrade.domain.model.AiSignal.HOLD -> null
        }
    }
}


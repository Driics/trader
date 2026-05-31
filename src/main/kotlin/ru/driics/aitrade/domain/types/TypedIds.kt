package ru.driics.aitrade.domain.types

import com.fasterxml.jackson.annotation.JsonValue
import java.util.Locale

/**
 * Instrument identifier (e.g., "BTC-USDT-SWAP").
 * Type-safe wrapper preventing accidental mixing with other ID types.
 * Zero runtime overhead due to @JvmInline.
 */
@JvmInline
value class InstrumentId(@get:JsonValue val value: String) {
    init {
        require(value.isNotBlank()) { "InstrumentId cannot be blank" }
    }

    companion object {
        /** OKX-native default for perpetual swaps. Production builds config-driven IDs via [InstrumentResolver]. */
        const val DEFAULT_QUOTE = "USDT"
        const val DEFAULT_TYPE = "SWAP"

        /**
         * Builds an instId from its parts, honoring OKX's format: SPOT is `BASE-QUOTE`; derivatives
         * (SWAP/FUTURES) append the type as `BASE-QUOTE-TYPE`. The single source of truth for the format.
         */
        fun of(symbol: Symbol, quoteCurrency: String, instrumentType: String): InstrumentId {
            val quote = quoteCurrency.trim().uppercase(Locale.ROOT)
            val type = instrumentType.trim().uppercase(Locale.ROOT)
            require(quote.isNotBlank()) { "quoteCurrency must not be blank" }
            require(type.isNotBlank()) { "instrumentType must not be blank" }
            val raw = if (type == "SPOT") "${symbol.value}-$quote" else "${symbol.value}-$quote-$type"
            return InstrumentId(raw)
        }

        /** Convenience default (`BASE-USDT-SWAP`) for tests/backtests; production uses [InstrumentResolver]. */
        fun fromSymbol(symbol: Symbol): InstrumentId = of(symbol, DEFAULT_QUOTE, DEFAULT_TYPE)

        fun fromSymbol(symbolStr: String): InstrumentId = fromSymbol(Symbol.from(symbolStr))
    }

    /**
     * Extract symbol from instrument ID.
     * Example: "BTC-USDT-SWAP" -> Symbol("BTC")
     */
    fun toSymbol(): Symbol {
        val parts = value.split("-")
        require(parts.isNotEmpty()) { "Invalid instrument ID format: $value" }
        return Symbol.from(parts[0])
    }

    override fun toString(): String = value
}

/**
 * Order identifier from exchange.
 */
@JvmInline
value class OrderId(@get:JsonValue val value: String) {
    init {
        require(value.isNotBlank()) { "OrderId cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Position identifier (composite of instrument and side).
 */
@JvmInline
value class PositionId(@get:JsonValue val value: String) {
    init {
        require(value.isNotBlank()) { "PositionId cannot be blank" }
    }

    companion object {
        /**
         * Create PositionId from instrument and side.
         * Example: "BTC-USDT-SWAP|long"
         */
        fun from(instrumentId: InstrumentId, side: String): PositionId =
            PositionId("${instrumentId.value}|$side")
    }

    override fun toString(): String = value
}

/**
 * Extension functions for easy conversion from String.
 */
fun String.asInstrumentId(): InstrumentId = InstrumentId(this)
fun String.asOrderId(): OrderId = OrderId(this)
fun String.asPositionId(): PositionId = PositionId(this)

/**
 * Extension for Symbol to InstrumentId conversion.
 */
fun Symbol.toInstrumentId(): InstrumentId = InstrumentId.fromSymbol(this)
package ru.driics.aitrade.domain.types

import com.fasterxml.jackson.annotation.JsonValue

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
        /**
         * Create InstrumentId from symbol (e.g., "BTC" -> "BTC-USDT-SWAP").
         */
        fun fromSymbol(symbol: Symbol): InstrumentId =
            InstrumentId("${symbol.value}-USDT-SWAP")

        /**
         * Create InstrumentId from string symbol.
         */
        fun fromSymbol(symbolStr: String): InstrumentId =
            fromSymbol(Symbol.from(symbolStr))
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
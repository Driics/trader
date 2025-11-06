package ru.driics.aitrade.domain.types

import java.util.Locale

/**
 * Type-safe symbol representation with normalization.
 */
@JvmInline
value class Symbol(val value: String) {
    init {
        require(value.isNotBlank()) { "Symbol cannot be blank" }
        require(value == value.uppercase(Locale.ROOT)) { "Symbol must be uppercase" }
    }

    companion object {
        fun from(raw: String): Symbol = Symbol(raw.trim().uppercase(Locale.ROOT))
    }

    override fun toString(): String = value
}
package ru.driics.aitrade.domain.model

import java.util.Locale

enum class MarginMode {
    CROSS,
    ISOLATED;

    val asOkxApiValue: String
        get() = name.lowercase(Locale.US)

    companion object {
        fun fromString(value: String): MarginMode =
            when (value.lowercase(Locale.US).trim()) {
                "cross" -> CROSS
                "isolated" -> ISOLATED
                else -> throw IllegalArgumentException(
                    "Invalid margin mode: $value. Must be 'cross' or 'isolated'"
                )
            }
    }
}
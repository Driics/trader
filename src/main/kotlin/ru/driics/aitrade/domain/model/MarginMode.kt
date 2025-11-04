package ru.driics.aitrade.domain.model

enum class MarginMode {
    CROSS,
    ISOLATED;

    val asOkxApiValue: String
        get() = name.lowercase()

    companion object {
        fun fromString(value: String): MarginMode =
            when (value.lowercase().trim()) {
                "cross" -> CROSS
                "isolated" -> ISOLATED
                else -> throw IllegalArgumentException(
                    "Invalid margin mode: $value. Must be 'cross' or 'isolated'"
                )
            }
    }
}
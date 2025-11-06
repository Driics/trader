package ru.driics.aitrade.domain.types

import java.util.Locale

/**
 * Extension functions for Symbol normalization.
 */
fun String.asSymbol(): Symbol = Symbol.from(this)

fun String.normalizeSymbol(): String = this.trim().uppercase(Locale.ROOT)
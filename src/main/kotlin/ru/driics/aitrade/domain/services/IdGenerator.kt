package ru.driics.aitrade.domain.services

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

object IdGenerator {
    private val seq = AtomicLong(0)
    fun clOrdId(symbol: String, clock: Clock = Clock.systemUTC()): String {
        val head = ("AI" + symbol.filter { it.isLetterOrDigit() }.uppercase()).take(8)
        val ts36 = clock.instant().toEpochMilli().toString(36).uppercase()
        val c36 = (seq.incrementAndGet() and 0xFFFF).toString(36).uppercase()
        val raw = head + ts36 + c36
        return if (raw.length <= 32) raw else raw.takeLast(32)
    }
    fun safeTag(raw: String?, maxLen: Int = 16, fallback: String = "AISIGNAL"): String? =
        (raw ?: fallback).filter { it.isLetterOrDigit() }.uppercase().take(maxLen).ifBlank { null }
}
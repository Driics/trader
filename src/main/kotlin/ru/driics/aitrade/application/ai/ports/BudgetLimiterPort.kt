package ru.driics.aitrade.application.ai.ports

/**
 * Port for AI budget rate limiting.
 */
interface BudgetLimiterPort {
    fun tryConsume(): Boolean
    fun getAvailableTokens(): Long
}


package ru.driics.aitrade.application.ai

import ru.driics.aitrade.common.logging.logger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Counter
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Token bucket rate limiter for AI API budget control.
 * Limits the number of requests per minute to control costs.
 */
class AiBudgetLimiter(
    private val budgetPerMinute: Long,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val log = logger<AiBudgetLimiter>()
    }

    private val tokens = AtomicLong(budgetPerMinute.toLong())
    private val lastRefill = AtomicReference(clock.instant())
    private val refillInterval = Duration.ofMinutes(1)

    private val budgetExceededCounter: Counter = meterRegistry.counter("ai.budget.exceeded")

    /**
     * Attempts to consume one token from the bucket.
     * Returns true if token was consumed, false if budget exceeded.
     */
    fun tryConsume(): Boolean {
        refillTokens()

        val current = tokens.get()
        if (current <= 0) {
            budgetExceededCounter.increment()
            log.warn { "AI budget exceeded: $budgetPerMinute requests/min limit reached" }
            return false
        }

        val updated = tokens.decrementAndGet()
        if (updated < 0) {
            // Race condition: another thread consumed the last token
            tokens.incrementAndGet()
            budgetExceededCounter.increment()
            return false
        }

        return true
    }

    /**
     * Gets current available tokens (approximate, for monitoring).
     */
    fun getAvailableTokens(): Long {
        refillTokens()
        return tokens.get().coerceAtLeast(0)
    }

    private fun refillTokens() {
        val now = clock.instant()
        val last = lastRefill.get()
        val elapsed = Duration.between(last, now)

        if (elapsed >= refillInterval) {
            // Refill tokens
            val intervals = elapsed.toMinutes() / refillInterval.toMinutes()
            val tokensToAdd = intervals * budgetPerMinute

            if (lastRefill.compareAndSet(last, now)) {
                val current = tokens.get()
                val newValue = (current + tokensToAdd).coerceAtMost(budgetPerMinute.toLong())
                tokens.set(newValue)
                log.debug { "Refilled AI budget: $tokensToAdd tokens, new balance: $newValue" }
            }
        }
    }
}


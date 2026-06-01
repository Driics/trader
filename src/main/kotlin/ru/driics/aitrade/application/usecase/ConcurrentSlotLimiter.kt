package ru.driics.aitrade.application.usecase

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free bounded counter that enforces the per-cycle concurrent-position cap (S3).
 *
 * The problem it solves: [ru.driics.aitrade.domain.risk.RiskContext.openPositionsCount] is
 * frozen at the start of a cycle, but order plans execute concurrently (`flatMapMerge`). A naive
 * `count >= cap` check lets N racing plans all read the same sub-cap count and all pass — placing
 * more positions than the cap allows. This counter reserves a slot atomically via CAS, so at most
 * `(limit - base)` reservations can ever succeed, regardless of how many callers race.
 *
 * Contract: a caller that [tryReserve]s a slot but then fails to place its order MUST [release] it,
 * otherwise that capacity is lost for the rest of the cycle.
 *
 * One instance per cycle (seed [baseCount] with the frozen open-position count). Safe for
 * concurrent use across the cycle's plan coroutines.
 */
class ConcurrentSlotLimiter(private val baseCount: Int) {

    private val reserved = AtomicInteger(0)

    /** Effective count = frozen base + slots reserved so far this cycle. */
    fun effectiveCount(): Int = baseCount + reserved.get()

    /**
     * Atomically reserves a slot iff the effective count is still strictly below [limit].
     *
     * @return true if a slot was reserved (caller may proceed; MUST [release] on failure to place),
     *         false if the cap is already reached.
     */
    fun tryReserve(limit: Int): Boolean {
        while (true) {
            val current = reserved.get()
            if (baseCount + current >= limit) return false
            if (reserved.compareAndSet(current, current + 1)) return true
        }
    }

    /** Releases a previously-reserved slot (the order did not get placed). */
    fun release() {
        reserved.decrementAndGet()
    }
}

package ru.driics.aitrade.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic proof of the S3 race fix. The bug it guards against is a TOCTOU: many concurrent
 * order plans read the same sub-cap count and all pass. We assert that, under heavy real-thread
 * contention, NO MORE than (limit - base) reservations ever succeed.
 */
class ConcurrentSlotLimiterTest {

    @Test
    fun `at most one slot is granted when only one is free, under heavy contention`() {
        val base = 4
        val limit = 5 // exactly one slot free
        val limiter = ConcurrentSlotLimiter(base)

        val threads = 64
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val granted = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                start.await() // line all threads up to maximize the race window
                if (limiter.tryReserve(limit)) granted.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS), "all worker threads should finish")
        pool.shutdownNow()

        assertEquals(1, granted.get(), "only (limit - base) = 1 reservation may succeed")
        assertEquals(limit, limiter.effectiveCount())
    }

    @Test
    fun `grants exactly the free capacity under contention`() {
        val base = 2
        val limit = 7 // five slots free
        val limiter = ConcurrentSlotLimiter(base)

        val threads = 64
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val granted = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                start.await()
                if (limiter.tryReserve(limit)) granted.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(5, granted.get(), "exactly (limit - base) = 5 reservations may succeed")
        assertEquals(limit, limiter.effectiveCount())
    }

    @Test
    fun `base already at limit grants nothing`() {
        val limiter = ConcurrentSlotLimiter(baseCount = 5)
        assertFalse(limiter.tryReserve(limit = 5))
        assertEquals(5, limiter.effectiveCount())
    }

    @Test
    fun `release returns a slot for reuse`() {
        val limiter = ConcurrentSlotLimiter(baseCount = 4)
        assertTrue(limiter.tryReserve(limit = 5))   // takes the only free slot
        assertFalse(limiter.tryReserve(limit = 5))  // cap reached
        limiter.release()                           // give it back
        assertTrue(limiter.tryReserve(limit = 5), "a released slot must be reservable again")
    }
}

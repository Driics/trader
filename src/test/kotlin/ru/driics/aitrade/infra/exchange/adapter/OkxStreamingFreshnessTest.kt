package ru.driics.aitrade.infra.exchange.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Boundary specs for the real-time price freshness guard ([resolveFreshPrice]).
 *
 * This pure function carries the ENTIRE safety argument for consuming a WS price on the money path:
 * "fresh-or-null, so a caller can fall back to REST and never act on a stale price." A bug that
 * returns stale-as-fresh is the one way streaming consumption could regress order pricing, so the
 * boundaries (not-connected, no-cache, just-under, exactly-at, just-over maxAge) are pinned hard.
 */
class OkxStreamingFreshnessTest {

    private val price = BigDecimal("50000.1")
    private val maxAgeMs = 1_000L
    private val received = 0L

    /** A monotonic "now" that sits exactly [ageMs] after the cached receive time. */
    private fun nowAtAgeMs(ageMs: Long): Long = received + ageMs * 1_000_000

    @Test
    fun `returns the price when connected and within max age`() {
        val r = resolveFreshPrice(price, received, connected = true, nowNanos = nowAtAgeMs(500), maxAgeMs = maxAgeMs)
        assertEquals(price, r)
    }

    @Test
    fun `treats exactly max age as fresh (inclusive boundary)`() {
        val r = resolveFreshPrice(price, received, connected = true, nowNanos = nowAtAgeMs(1_000), maxAgeMs = maxAgeMs)
        assertEquals(price, r)
    }

    @Test
    fun `returns null one millisecond past max age`() {
        val r = resolveFreshPrice(price, received, connected = true, nowNanos = nowAtAgeMs(1_001), maxAgeMs = maxAgeMs)
        assertNull(r)
    }

    @Test
    fun `returns null when the socket is not connected even if the price is fresh`() {
        val r = resolveFreshPrice(price, received, connected = false, nowNanos = nowAtAgeMs(0), maxAgeMs = maxAgeMs)
        assertNull(r)
    }

    @Test
    fun `returns null when no price has been cached yet`() {
        val r = resolveFreshPrice(null, received, connected = true, nowNanos = nowAtAgeMs(0), maxAgeMs = maxAgeMs)
        assertNull(r)
    }
}

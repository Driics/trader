package ru.driics.aitrade.application.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Characterization tests pinning the CURRENT behaviour of IdempotencyService
 * (safety net before the S8 window/TTL-alignment change). These document how
 * dedup keys are currently derived — including the floor-window boundary
 * behaviour that S8 will deliberately change.
 */
class IdempotencyServiceTest {

    /** Mutable clock so we can advance time within a single cache instance (TTL tests). */
    private class MutableClock(
        var now: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(z: ZoneId): Clock = MutableClock(now, z)
        override fun instant(): Instant = now
    }

    private val t0 = Instant.parse("2026-05-29T12:00:00Z")

    private fun args(
        coin: String = "BTC",
        signal: AiSignal = AiSignal.BUY,
        tp: BigDecimal? = BigDecimal("70000"),
        sl: BigDecimal? = BigDecimal("60000"),
        leverage: Int? = 10,
    ) = AiTradeSignalArgs(
        coin = coin,
        signal = signal,
        profitTarget = tp,
        stopLoss = sl,
        leverage = leverage,
    )

    @Test
    fun `recorded signal is reported as duplicate`() {
        val svc = IdempotencyService(Clock.fixed(t0, ZoneOffset.UTC))
        val key = svc.signalKey("BTC", args(), BigDecimal("65000"))

        assertFalse(svc.isDuplicate(key), "fresh key should not be duplicate")
        svc.recordSignal(key)
        assertTrue(svc.isDuplicate(key), "recorded key should be duplicate")
    }

    @Test
    fun `unknown key is not a duplicate`() {
        val svc = IdempotencyService(Clock.fixed(t0, ZoneOffset.UTC))
        assertFalse(svc.isDuplicate("never-seen"))
    }

    @Test
    fun `signalKey is deterministic for identical inputs in the same window`() {
        val svc = IdempotencyService(Clock.fixed(t0, ZoneOffset.UTC))
        val a = svc.signalKey("BTC", args(), BigDecimal("65000"))
        val b = svc.signalKey("BTC", args(), BigDecimal("65000"))
        assertEquals(a, b)
    }

    @Test
    fun `signalKey differs when signal direction differs`() {
        val svc = IdempotencyService(Clock.fixed(t0, ZoneOffset.UTC))
        val buy = svc.signalKey("BTC", args(signal = AiSignal.BUY), BigDecimal("65000"))
        val sell = svc.signalKey("BTC", args(signal = AiSignal.SELL), BigDecimal("65000"))
        assertNotEquals(buy, sell)
    }

    @Test
    fun `signalKey is case-insensitive on symbol`() {
        val svc = IdempotencyService(Clock.fixed(t0, ZoneOffset.UTC))
        val lower = svc.signalKey("btc", args(coin = "btc"), BigDecimal("65000"))
        val upper = svc.signalKey("BTC", args(coin = "BTC"), BigDecimal("65000"))
        assertEquals(lower, upper)
    }

    @Test
    fun `CURRENT behaviour - signalKey changes across a floor-window boundary (S8 will change this)`() {
        // Two calls 150s apart straddle the 2-minute floor window, so the keys differ today
        // even though the signals are otherwise identical. S8 aligns this with the sliding TTL.
        val early = IdempotencyService(Clock.fixed(t0, ZoneOffset.UTC))
        val later = IdempotencyService(Clock.fixed(t0.plusSeconds(150), ZoneOffset.UTC))

        val k1 = early.signalKey("BTC", args(), BigDecimal("65000"))
        val k2 = later.signalKey("BTC", args(), BigDecimal("65000"))
        assertNotEquals(k1, k2, "today the floor-window makes near-identical signals look distinct")
    }

    @Test
    fun `duplicate expires after the TTL window`() {
        val clock = MutableClock(t0)
        val svc = IdempotencyService(clock, ttlMinutes = 2)
        val key = svc.signalKey("BTC", args(), BigDecimal("65000"))

        svc.recordSignal(key)
        assertTrue(svc.isDuplicate(key))

        clock.now = t0.plusSeconds(2 * 60) // exactly at TTL -> expired (elapsed >= ttl)
        assertFalse(svc.isDuplicate(key), "entry should expire once elapsed >= ttl")
    }

    @Test
    fun `generateClOrdId is deterministic and OKX-safe`() {
        val svc = IdempotencyService(Clock.fixed(t0, ZoneOffset.UTC))
        val ts = t0.toEpochMilli()
        val a = svc.generateClOrdId("BTC", args(), BigDecimal("65000"), ts)
        val b = svc.generateClOrdId("BTC", args(), BigDecimal("65000"), ts)

        assertEquals(a, b, "same inputs -> same clOrdId")
        assertTrue(a.length <= 32, "clOrdId must be <= 32 chars, was ${a.length}: $a")
        assertTrue(a.startsWith("AI"), "clOrdId should carry the AI prefix: $a")
    }
}

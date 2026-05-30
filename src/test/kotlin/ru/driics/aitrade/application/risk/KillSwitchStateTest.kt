package ru.driics.aitrade.application.risk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KillSwitchStateTest {

    private fun fixedClock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)

    @Test
    fun `initial snapshot is disabled`() {
        val state = KillSwitchState(fixedClock(Instant.parse("2026-05-27T12:00:00Z")))
        val snap = state.snapshot()
        assertFalse(snap.enabled)
        assertNull(snap.reason)
        assertNull(snap.since)
        assertNull(snap.source)
    }

    @Test
    fun `trip sets enabled, reason, since, source`() {
        val now = Instant.parse("2026-05-27T12:00:00Z")
        val state = KillSwitchState(fixedClock(now))
        val snap = state.trip("operator paused", KillSwitchSnapshot.Source.MANUAL)
        assertTrue(snap.enabled)
        assertEquals("operator paused", snap.reason)
        assertEquals(now, snap.since)
        assertEquals(KillSwitchSnapshot.Source.MANUAL, snap.source)
    }

    @Test
    fun `clear resets to disabled`() {
        val state = KillSwitchState(fixedClock(Instant.parse("2026-05-27T12:00:00Z")))
        state.trip("x", KillSwitchSnapshot.Source.MANUAL)
        val snap = state.clear()
        assertFalse(snap.enabled)
        assertNull(snap.source)
    }

    @Test
    fun `AUTO_DAILY_LOSS auto-clears at next UTC day`() {
        val tripTime = Instant.parse("2026-05-27T23:50:00Z")
        val nextDay = Instant.parse("2026-05-28T00:05:00Z")

        val rolled = KillSwitchState(fixedClock(nextDay))
        rolled.overrideForTest(
            KillSwitchSnapshot(
                enabled = true,
                reason = "daily loss",
                since = tripTime,
                source = KillSwitchSnapshot.Source.AUTO_DAILY_LOSS,
            )
        )
        val rolledSnap = rolled.snapshot()
        assertFalse(rolledSnap.enabled, "AUTO source must auto-clear on next UTC day")
    }

    @Test
    fun `AUTO trip does not overwrite an active MANUAL trip`() {
        val state = KillSwitchState(fixedClock(Instant.parse("2026-05-27T12:00:00Z")))
        val manual = state.trip("operator pause", KillSwitchSnapshot.Source.MANUAL)
        val attempted = state.trip("daily loss", KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
        assertEquals(KillSwitchSnapshot.Source.MANUAL, attempted.source)
        assertEquals("operator pause", attempted.reason)
        assertEquals(manual, state.snapshot())
    }

    @Test
    fun `MANUAL trip overwrites an existing AUTO trip`() {
        val state = KillSwitchState(fixedClock(Instant.parse("2026-05-27T12:00:00Z")))
        state.trip("daily loss", KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
        val manual = state.trip("operator pause", KillSwitchSnapshot.Source.MANUAL)
        assertEquals(KillSwitchSnapshot.Source.MANUAL, manual.source)
        assertEquals("operator pause", manual.reason)
    }

    @Test
    fun `MANUAL trip survives UTC day rollover`() {
        val tripTime = Instant.parse("2026-05-27T23:50:00Z")
        val nextDay = Instant.parse("2026-05-28T00:05:00Z")

        val state = KillSwitchState(fixedClock(nextDay))
        state.overrideForTest(
            KillSwitchSnapshot(
                enabled = true,
                reason = "operator",
                since = tripTime,
                source = KillSwitchSnapshot.Source.MANUAL,
            )
        )
        assertTrue(state.snapshot().enabled, "MANUAL trip must NOT auto-clear")
    }
}

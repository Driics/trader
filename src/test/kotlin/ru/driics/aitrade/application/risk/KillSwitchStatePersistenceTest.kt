package ru.driics.aitrade.application.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot
import ru.driics.aitrade.infra.risk.FileKillSwitchStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S2: durable kill-switch. State must survive a "restart" (a fresh KillSwitchState reading the same
 * file), and an unreadable file on boot must fail CLOSED.
 */
class KillSwitchStatePersistenceTest {

    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `a MANUAL trip survives a restart`() {
        val file = dir.resolve("kill-switch.json")
        KillSwitchState(clock, FileKillSwitchStore(file))
            .trip("operator pause", KillSwitchSnapshot.Source.MANUAL)

        val restarted = KillSwitchState(clock, FileKillSwitchStore(file))
        restarted.rehydrate()
        val snap = restarted.snapshot()

        assertTrue(snap.enabled)
        assertEquals(KillSwitchSnapshot.Source.MANUAL, snap.source)
        assertEquals("operator pause", snap.reason)
    }

    @Test
    fun `clear persists so a restart rehydrates disabled`() {
        val file = dir.resolve("kill-switch.json")
        val first = KillSwitchState(clock, FileKillSwitchStore(file))
        first.trip("x", KillSwitchSnapshot.Source.MANUAL)
        first.clear()

        val restarted = KillSwitchState(clock, FileKillSwitchStore(file))
        restarted.rehydrate()

        assertFalse(restarted.snapshot().enabled)
    }

    @Test
    fun `corrupt persistence on boot fails closed with a MANUAL kill`() {
        val file = dir.resolve("kill-switch.json")
        Files.writeString(file, "}}garbage{{")

        val state = KillSwitchState(clock, FileKillSwitchStore(file))
        state.rehydrate()
        val snap = state.snapshot()

        assertTrue(snap.enabled, "a corrupt store must fail closed, not silently allow trading")
        assertEquals(KillSwitchSnapshot.Source.MANUAL, snap.source)
    }
}

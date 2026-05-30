package ru.driics.aitrade.infra.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class FileKillSwitchStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun store() = FileKillSwitchStore(dir.resolve("kill-switch.json"))

    @Test
    fun `load returns null when nothing has been persisted`() {
        assertNull(store().load())
    }

    @Test
    fun `round-trips a MANUAL trip`() {
        val s = store()
        val snap = KillSwitchSnapshot(
            enabled = true,
            reason = "ops pause",
            since = Instant.parse("2026-05-29T10:00:00Z"),
            source = KillSwitchSnapshot.Source.MANUAL,
        )
        s.save(snap)
        assertEquals(snap, s.load())
    }

    @Test
    fun `round-trips the disabled snapshot`() {
        val s = store()
        s.save(KillSwitchSnapshot.disabled())
        assertEquals(KillSwitchSnapshot.disabled(), s.load())
    }

    @Test
    fun `save overwrites the previous value`() {
        val s = store()
        s.save(
            KillSwitchSnapshot(
                enabled = true,
                reason = "first",
                since = Instant.parse("2026-05-29T10:00:00Z"),
                source = KillSwitchSnapshot.Source.AUTO_DAILY_LOSS,
            )
        )
        s.save(KillSwitchSnapshot.disabled())
        assertEquals(KillSwitchSnapshot.disabled(), s.load())
    }

    @Test
    fun `corrupt file throws so the caller can fail closed`() {
        val path = dir.resolve("kill-switch.json")
        Files.writeString(path, "{ this is not valid json")
        assertThrows(Exception::class.java) { FileKillSwitchStore(path).load() }
    }
}

package ru.driics.aitrade.infra.risk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ru.driics.aitrade.domain.ports.KillSwitchStore
import ru.driics.aitrade.domain.risk.KillSwitchSnapshot
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * JSON file persistence for the kill-switch (S2).
 *
 * Writes are atomic: the snapshot is written to a temp file in the same directory and then moved
 * over the target (atomic move when the filesystem supports it), so a crash mid-write can never
 * leave a half-written file. [load] returns null for an absent file (clean boot) but RETHROWS on a
 * corrupt/unreadable file so [ru.driics.aitrade.application.risk.KillSwitchState] can fail closed.
 *
 * `since` is persisted as epoch millis (not an Instant) to avoid depending on the JSR-310 Jackson
 * module being registered on the classpath.
 */
class FileKillSwitchStore(path: Path) : KillSwitchStore {

    private val target: Path = path.toAbsolutePath()
    private val mapper = jacksonObjectMapper()

    override fun load(): KillSwitchSnapshot? {
        if (!Files.exists(target)) return null
        val json = Files.readString(target)
        if (json.isBlank()) return null
        // Intentionally NOT caught: a parse failure propagates so the caller fails closed.
        return mapper.readValue<PersistedSnapshot>(json).toSnapshot()
    }

    override fun save(snapshot: KillSwitchSnapshot) {
        val dir = target.parent
        Files.createDirectories(dir)
        val json = mapper.writeValueAsString(PersistedSnapshot.from(snapshot))

        val tmp = Files.createTempFile(dir, "kill-switch", ".tmp")
        try {
            Files.writeString(tmp, json)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** On-disk shape. All fields defaulted so partial/legacy JSON still deserializes. */
    private data class PersistedSnapshot(
        val enabled: Boolean = false,
        val reason: String? = null,
        val sinceEpochMs: Long? = null,
        val source: KillSwitchSnapshot.Source? = null,
    ) {
        fun toSnapshot(): KillSwitchSnapshot = KillSwitchSnapshot(
            enabled = enabled,
            reason = reason,
            since = sinceEpochMs?.let { Instant.ofEpochMilli(it) },
            source = source,
        )

        companion object {
            fun from(s: KillSwitchSnapshot): PersistedSnapshot = PersistedSnapshot(
                enabled = s.enabled,
                reason = s.reason,
                sinceEpochMs = s.since?.toEpochMilli(),
                source = s.source,
            )
        }
    }
}

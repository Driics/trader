package ru.driics.aitrade.application.risk

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the kill-switch flag.
 *
 * AUTO_DAILY_LOSS trips auto-clear at UTC midnight rollover. MANUAL trips persist
 * until explicitly cleared via [clear]. See the X2 design spec for rationale.
 */
@Component
class KillSwitchState(private val clock: Clock) {

    private val ref = AtomicReference(KillSwitchSnapshot.disabled())

    fun snapshot(): KillSwitchSnapshot {
        val current = ref.get()
        if (current.shouldAutoClear(clock)) {
            val cleared = KillSwitchSnapshot.disabled()
            return if (ref.compareAndSet(current, cleared)) cleared else ref.get()
        }
        return current
    }

    fun trip(reason: String, source: KillSwitchSnapshot.Source): KillSwitchSnapshot {
        val next = KillSwitchSnapshot(
            enabled = true,
            reason = reason,
            since = clock.instant(),
            source = source,
        )
        ref.set(next)
        return next
    }

    fun clear(): KillSwitchSnapshot {
        val next = KillSwitchSnapshot.disabled()
        ref.set(next)
        return next
    }

    /** Test-only seam to set internal state without relying on a mutable clock. */
    internal fun overrideForTest(snapshot: KillSwitchSnapshot) {
        ref.set(snapshot)
    }

    private fun KillSwitchSnapshot.shouldAutoClear(clock: Clock): Boolean {
        if (!enabled || source != KillSwitchSnapshot.Source.AUTO_DAILY_LOSS) return false
        val since = this.since ?: return false
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val sinceDay = LocalDate.ofInstant(since, ZoneOffset.UTC)
        return today.isAfter(sinceDay)
    }
}

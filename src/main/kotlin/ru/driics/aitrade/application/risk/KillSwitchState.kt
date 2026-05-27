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

    /**
     * Returns the current snapshot. Side effect: if a stale AUTO_DAILY_LOSS trip
     * has crossed a UTC midnight, this method CAS-clears it. The CAS-miss path
     * is benign — a concurrent writer's value is fresher than what we'd have
     * written, so returning `ref.get()` is correct.
     */
    fun snapshot(): KillSwitchSnapshot {
        val current = ref.get()
        if (current.shouldAutoClear()) {
            val cleared = KillSwitchSnapshot.disabled()
            return if (ref.compareAndSet(current, cleared)) cleared else ref.get()
        }
        return current
    }

    /**
     * Trips the kill-switch. MANUAL trips always win and overwrite any prior state.
     * AUTO trips refuse to overwrite an existing MANUAL trip — the operator's
     * pause is sticky. Returns the snapshot that is actually in effect after the call.
     */
    fun trip(reason: String, source: KillSwitchSnapshot.Source): KillSwitchSnapshot {
        val next = KillSwitchSnapshot(
            enabled = true,
            reason = reason,
            since = clock.instant(),
            source = source,
        )
        if (source == KillSwitchSnapshot.Source.MANUAL) {
            ref.set(next)
            return next
        }
        // AUTO: yield to any active MANUAL trip; otherwise CAS-install ours.
        while (true) {
            val current = ref.get()
            if (current.enabled && current.source == KillSwitchSnapshot.Source.MANUAL) {
                return current
            }
            if (ref.compareAndSet(current, next)) return next
        }
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

    private fun KillSwitchSnapshot.shouldAutoClear(): Boolean {
        if (!enabled || source != KillSwitchSnapshot.Source.AUTO_DAILY_LOSS) return false
        val since = this.since ?: return false
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val sinceDay = LocalDate.ofInstant(since, ZoneOffset.UTC)
        return today.isAfter(sinceDay)
    }
}

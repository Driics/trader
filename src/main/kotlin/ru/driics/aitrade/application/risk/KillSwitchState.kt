package ru.driics.aitrade.application.risk

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import ru.driics.aitrade.common.logging.logger
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the kill-switch flag.
 *
 * AUTO_DAILY_LOSS trips auto-clear at UTC midnight rollover. MANUAL trips persist until explicitly
 * cleared via [clear]. S2: state is persisted to [store] on every mutation and rehydrated on boot
 * so it survives restarts; an unreadable store on boot fails CLOSED. See the X2 design spec.
 */
@Component
class KillSwitchState(
    private val clock: Clock,
    private val store: KillSwitchStore = KillSwitchStore.NoOp,
) {
    private companion object {
        val log = logger<KillSwitchState>()
    }

    private val ref = AtomicReference(KillSwitchSnapshot.disabled())

    /**
     * S2: rehydrate persisted state on boot. A clean (absent) store leaves us disabled. A corrupt or
     * unreadable store fails CLOSED — we install a MANUAL kill so we never resume trading on a state
     * we cannot verify; an operator must clear it explicitly after checking the system.
     */
    @PostConstruct
    fun rehydrate() {
        val restored = try {
            store.load()
        } catch (e: Exception) {
            log.error(e) { "Kill-switch store unreadable on boot — failing closed (manual kill installed)" }
            val failClosed = KillSwitchSnapshot(
                enabled = true,
                reason = "kill-switch persistence unreadable on boot; clear manually after verification",
                since = clock.instant(),
                source = KillSwitchSnapshot.Source.MANUAL,
            )
            ref.set(failClosed)
            persistQuietly(failClosed)
            return
        }
        if (restored != null) {
            ref.set(restored)
            log.info { "Rehydrated kill-switch: enabled=${restored.enabled}, source=${restored.source}" }
        }
    }

    /**
     * Returns the current snapshot. Side effect: if a stale AUTO_DAILY_LOSS trip has crossed a UTC
     * midnight, this method CAS-clears it (and persists the clear). The CAS-miss path is benign — a
     * concurrent writer's value is fresher than what we'd have written, so returning `ref.get()`
     * is correct.
     */
    fun snapshot(): KillSwitchSnapshot {
        val current = ref.get()
        if (current.shouldAutoClear()) {
            val cleared = KillSwitchSnapshot.disabled()
            return if (ref.compareAndSet(current, cleared)) {
                persistQuietly(cleared)
                cleared
            } else {
                ref.get()
            }
        }
        return current
    }

    /**
     * Trips the kill-switch. MANUAL trips always win and overwrite any prior state. AUTO trips refuse
     * to overwrite an existing MANUAL trip — the operator's pause is sticky. Returns the snapshot
     * that is actually in effect after the call, and persists it.
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
            persistQuietly(next)
            return next
        }
        // AUTO: yield to any active MANUAL trip; otherwise CAS-install ours.
        while (true) {
            val current = ref.get()
            if (current.enabled && current.source == KillSwitchSnapshot.Source.MANUAL) {
                return current
            }
            if (ref.compareAndSet(current, next)) {
                persistQuietly(next)
                return next
            }
        }
    }

    fun clear(): KillSwitchSnapshot {
        val next = KillSwitchSnapshot.disabled()
        ref.set(next)
        persistQuietly(next)
        return next
    }

    /** Test-only seam to set internal state without relying on a mutable clock. */
    internal fun overrideForTest(snapshot: KillSwitchSnapshot) {
        ref.set(snapshot)
    }

    private fun persistQuietly(snapshot: KillSwitchSnapshot) {
        runCatching { store.save(snapshot) }.onFailure {
            log.warn(it) { "Failed to persist kill-switch state; in-memory state remains authoritative" }
        }
    }

    private fun KillSwitchSnapshot.shouldAutoClear(): Boolean {
        if (!enabled || source != KillSwitchSnapshot.Source.AUTO_DAILY_LOSS) return false
        val since = this.since ?: return false
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val sinceDay = LocalDate.ofInstant(since, ZoneOffset.UTC)
        return today.isAfter(sinceDay)
    }
}

package ru.driics.aitrade.application.risk

import ru.driics.aitrade.domain.risk.KillSwitchSnapshot

/**
 * Durable persistence for the kill-switch snapshot (S2).
 *
 * Survives process restarts so an operator pause (MANUAL) or an automatic daily-loss trip
 * (AUTO_DAILY_LOSS) is not silently lost on redeploy and we don't resume trading blind.
 */
interface KillSwitchStore {

    /**
     * Loads the persisted snapshot.
     *
     * @return the persisted snapshot, or `null` if nothing has ever been persisted (clean first boot).
     * @throws Exception if the store exists but cannot be read/parsed. Callers MUST fail closed on a
     *         thrown error — a corrupt persistence file means we cannot prove it is safe to trade.
     */
    fun load(): KillSwitchSnapshot?

    /** Persists [snapshot] durably, replacing any previous value atomically. */
    fun save(snapshot: KillSwitchSnapshot)

    /** No-op store: used as the default so unit tests need no filesystem and never persist. */
    object NoOp : KillSwitchStore {
        override fun load(): KillSwitchSnapshot? = null
        override fun save(snapshot: KillSwitchSnapshot) = Unit
    }
}

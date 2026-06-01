package ru.driics.aitrade.domain.risk

import java.time.Instant

data class KillSwitchSnapshot(
    val enabled: Boolean,
    val reason: String?,
    val since: Instant?,
    val source: Source?,
) {
    enum class Source { MANUAL, AUTO_DAILY_LOSS }

    companion object {
        fun disabled(): KillSwitchSnapshot =
            KillSwitchSnapshot(enabled = false, reason = null, since = null, source = null)
    }
}

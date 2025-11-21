package ru.driics.aitrade.controller.util

import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import java.time.Clock
import java.time.Duration

/**
 * Utility functions for trading system operations.
 */
object TradingSystemUtils {

    /**
     * Formats duration in seconds to a human-readable string (e.g., "2d 3h 15m 30s").
     */
    fun formatDuration(seconds: Long): String {
        val duration = Duration.ofSeconds(seconds)
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        val secs = duration.seconds % 60

        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            append("${secs}s")
        }.trim()
    }

    /**
     * Estimates when the next execution should occur based on the last update time.
     */
    fun estimateNextExecution(orchestrator: UpdateCycleOrchestrator, clock: Clock = Clock.systemUTC()): String {
        val lastUpdate = orchestrator.getLastUpdateTime() ?: orchestrator.getSessionStartTime()
        val nextExecution = lastUpdate + 180000 // 3 minutes
        val now = clock.instant().toEpochMilli()

        return if (nextExecution > now) {
            val secondsUntil = (nextExecution - now) / 1000
            "in ${formatDuration(secondsUntil)}"
        } else {
            "overdue (should trigger soon)"
        }
    }
}


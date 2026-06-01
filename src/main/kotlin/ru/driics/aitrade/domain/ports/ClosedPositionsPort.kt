package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.ClosedPosition

/** Outbound port: fetch positions closed strictly after [sinceMs] (newest-first source, returned as a list). */
interface ClosedPositionsPort {
    suspend fun closedSince(sinceMs: Long): List<ClosedPosition>
}

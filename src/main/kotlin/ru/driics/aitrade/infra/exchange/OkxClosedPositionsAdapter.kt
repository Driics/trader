package ru.driics.aitrade.infra.exchange

import org.springframework.stereotype.Component
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.service.okx.OkxAccountClient
import java.math.BigDecimal

/**
 * Fetches closed positions via OKX positions-history and maps them to [ClosedPosition], keeping only
 * closes strictly newer than `sinceMs`. Paginates (by `posId`) until a page goes at/under the cutoff or
 * is empty, capped at [MAX_PAGES] so a misbehaving feed cannot loop. Read failures yield an empty list.
 */
@Component
class OkxClosedPositionsAdapter(
    private val account: OkxAccountClient,
) : ClosedPositionsPort {

    private companion object {
        val log = logger<OkxClosedPositionsAdapter>()
        const val MAX_PAGES = 10
        const val PAGE = 100
    }

    override suspend fun closedSince(sinceMs: Long): List<ClosedPosition> {
        val out = ArrayList<ClosedPosition>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val resp = account.fetchPositionsHistory(after = after, limit = PAGE) ?: return out
            if (!resp.isSuccess() || resp.data.isEmpty()) return out
            for (d in resp.data) {
                val closeMs = d.updatedTime.toLongOrNull() ?: 0L
                if (closeMs > sinceMs) {
                    out += ClosedPosition(
                        posId = d.posId,
                        instId = d.instId,
                        side = d.direction.ifBlank { null },
                        realizedPnl = d.realizedPnl.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                        openTimeMs = d.createdTime.toLongOrNull() ?: 0L,
                        closeTimeMs = closeMs,
                    )
                }
            }
            // History is newest-first; once a full page no longer beats the cutoff, older pages won't either.
            if (resp.data.all { (it.updatedTime.toLongOrNull() ?: 0L) <= sinceMs }) return out
            after = resp.data.last().posId
        }
        log.warn { "positions-history hit MAX_PAGES=$MAX_PAGES; returning ${out.size} closes" }
        return out
    }
}

package ru.driics.aitrade.infra.exchange

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.model.OkxApiResponse
import ru.driics.aitrade.domain.model.OkxPositionHistoryData
import ru.driics.aitrade.service.okx.OkxAccountClient
import java.math.BigDecimal

class OkxClosedPositionsAdapterTest {
    private val account = mockk<OkxAccountClient>()
    private val adapter = OkxClosedPositionsAdapter(account)

    @Test
    fun `maps history rows and drops those at or before sinceMs`() = runBlocking {
        // First call returns two rows; second call (pagination with after=posId) returns empty page
        coEvery { account.fetchPositionsHistory(any(), any()) } returnsMany listOf(
            OkxApiResponse(
                code = "0", message = "", data = listOf(
                    OkxPositionHistoryData(instId = "BTC-USDT-SWAP", posId = "p2", realizedPnl = "10",
                        createdTime = "1000", updatedTime = "3000", direction = "long"),
                    OkxPositionHistoryData(instId = "ETH-USDT-SWAP", posId = "p1", realizedPnl = "-4",
                        createdTime = "500", updatedTime = "2000", direction = "short"),
                )
            ),
            OkxApiResponse(code = "0", message = "", data = emptyList()),
        )
        val out = adapter.closedSince(sinceMs = 2000L)
        assertEquals(1, out.size)               // p1 closeTime=2000 is NOT strictly after 2000 -> dropped
        assertEquals("p2", out[0].posId)
        assertEquals(0, BigDecimal("10").compareTo(out[0].realizedPnl))
        assertEquals(3000L, out[0].closeTimeMs)
    }

    @Test
    fun `returns empty when the read fails (null) — fail-safe`() = runBlocking {
        coEvery { account.fetchPositionsHistory(any(), any()) } returns null
        assertEquals(emptyList<Any>(), adapter.closedSince(0L))
    }
}

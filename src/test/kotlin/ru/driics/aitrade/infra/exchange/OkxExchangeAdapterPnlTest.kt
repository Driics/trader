package ru.driics.aitrade.infra.exchange

import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.OkxApiResponse
import ru.driics.aitrade.domain.model.OkxBillData
import ru.driics.aitrade.domain.types.InstrumentResolver
import ru.driics.aitrade.domain.types.TradeResult
import ru.driics.aitrade.infra.cache.CachedIndicatorCalculator
import ru.driics.aitrade.infra.cache.SmartCacheStrategy
import ru.driics.aitrade.service.OkxRestClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Contract spec for B0: [OkxExchangeAdapter.getTodaysRealizedPnlUsd].
 *
 * Since this code is UNVERIFIED against live OKX, these fixtures ARE the specification of the
 * assumed `/account/bills` behaviour. The pagination/UTC-window/sign assertions are what protect
 * the daily-loss cap from silently under-reporting losses.
 */
class OkxExchangeAdapterPnlTest {

    private val rest = mockk<OkxRestClient>()
    private val tracer = mockk<Tracer>(relaxed = true)
    private val smartCache = mockk<SmartCacheStrategy>(relaxed = true)
    private val indicators = mockk<CachedIndicatorCalculator>(relaxed = true)

    // now = 2026-05-29T12:00:00Z  ->  UTC midnight = 2026-05-29T00:00:00Z
    private val now = Instant.parse("2026-05-29T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val midnightMs = Instant.parse("2026-05-29T00:00:00Z").toEpochMilli()

    private fun adapter() = OkxExchangeAdapter(
        rest = rest,
        tradingProperties = TradingProperties(currencies = listOf("BTC")),
        tracer = tracer,
        smartCache = smartCache,
        indicators = indicators,
        clock = clock,
        instrumentResolver = InstrumentResolver("USDT", "SWAP"),
    )

    private fun bill(id: String, tsMs: Long, pnl: String) =
        OkxBillData(billId = id, timestamp = tsMs.toString(), pnl = pnl)

    private fun ok(data: List<OkxBillData>) = OkxApiResponse(code = "0", message = "", data = data)

    @Test
    fun `no bills today yields zero`() = runBlocking {
        coEvery { rest.fetchBills(after = null, limit = any()) } returns ok(emptyList())

        val result = adapter().getTodaysRealizedPnlUsd(now)

        assertTrue(result is TradeResult.Success)
        assertEquals(0, BigDecimal.ZERO.compareTo((result as TradeResult.Success).value))
    }

    @Test
    fun `sums only bills at or after UTC midnight, preserving loss sign`() = runBlocking {
        val todayLoss = bill("b3", midnightMs + 3_600_000, "-20.5")
        val todayGain = bill("b2", midnightMs + 60_000, "5.0")
        val yesterday = bill("b1", midnightMs - 1, "-1000") // excluded: before today's UTC midnight
        coEvery { rest.fetchBills(after = null, limit = any()) } returns ok(listOf(todayLoss, todayGain, yesterday))

        val result = adapter().getTodaysRealizedPnlUsd(now) as TradeResult.Success

        // -20.5 + 5.0 = -15.5 ; yesterday's -1000 is NOT counted
        assertEquals(0, BigDecimal("-15.5").compareTo(result.value))
    }

    @Test
    fun `loss split across pages is fully summed and crosses the daily cap`() = runBlocking {
        // Page 1 is full (== page limit) so pagination MUST continue to page 2.
        val page1 = (1..100).map { bill("p1_$it", midnightMs + 100_000L + it, "-0.30") } // sum -30.00
        val page2 = listOf(
            bill("p2_a", midnightMs + 50_000, "-25.00"),  // today
            bill("p2_b", midnightMs - 10, "-9999"),       // before midnight -> excluded + stops paging
        )
        coEvery { rest.fetchBills(after = null, limit = any()) } returns ok(page1)
        coEvery { rest.fetchBills(after = "p1_100", limit = any()) } returns ok(page2)

        val result = adapter().getTodaysRealizedPnlUsd(now) as TradeResult.Success

        // -30.00 (page 1) + -25.00 (page 2 today) = -55.00 — undercounting would hide the breach.
        assertEquals(0, BigDecimal("-55.00").compareTo(result.value))
        assertTrue(result.value <= BigDecimal("-50"), "summed loss must cross the 50 USD daily cap")
    }

    @Test
    fun `failed page read maps to Failure (fail-closed), never ZERO`() = runBlocking {
        coEvery { rest.fetchBills(after = null, limit = any()) } returns null

        val result = adapter().getTodaysRealizedPnlUsd(now)

        assertTrue(result is TradeResult.Failure, "a failed read must surface as Failure, not be mistaken for no-loss")
    }

    @Test
    fun `exchange error code maps to Failure`() = runBlocking {
        coEvery { rest.fetchBills(after = null, limit = any()) } returns
            OkxApiResponse(code = "50011", message = "rate limited", data = emptyList())

        val result = adapter().getTodaysRealizedPnlUsd(now)

        assertTrue(result is TradeResult.Failure)
    }

    @Test
    fun `mixed-currency and non-trade bills are all summed - current behavior, new diagnostics only log`() = runBlocking {
        val usdtTradeLoss = bill("t1", midnightMs + 1_000, "-30.0").copy(currency = "USDT", type = "2")
        val nonUsdContribution = bill("f1", midnightMs + 2_000, "-5.0").copy(currency = "BTC", type = "8")
        coEvery { rest.fetchBills(after = null, limit = any()) } returns ok(listOf(usdtTradeLoss, nonUsdContribution))

        val result = adapter().getTodaysRealizedPnlUsd(now) as TradeResult.Success

        // The cap still sums every bill's pnl regardless of ccy/type; the composition diagnostics only
        // LOG it (a non-USD WARN here), they do not change the total. -30.0 + -5.0 = -35.0.
        assertEquals(0, BigDecimal("-35.0").compareTo(result.value))
    }
}

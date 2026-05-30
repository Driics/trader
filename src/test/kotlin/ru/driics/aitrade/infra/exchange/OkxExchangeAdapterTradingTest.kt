package ru.driics.aitrade.infra.exchange

import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.domain.model.OkxPlaceOrderData
import ru.driics.aitrade.domain.types.InstrumentId
import ru.driics.aitrade.domain.types.TradeResult
import ru.driics.aitrade.infra.cache.CachedIndicatorCalculator
import ru.driics.aitrade.infra.cache.SmartCacheStrategy
import ru.driics.aitrade.service.OkxRestClient
import ru.driics.aitrade.service.okx.OkxCallOutcome
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S4: typed OKX call outcomes must map to the right [TradeResult] at the adapter boundary, so that
 * a TIMEOUT (order/leverage status unknown) is never mistaken for a clean rejection.
 */
class OkxExchangeAdapterTradingTest {

    private val rest = mockk<OkxRestClient>()
    private val clock = Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC)

    private fun adapter() = OkxExchangeAdapter(
        rest = rest,
        tradingProperties = TradingProperties(currencies = listOf("BTC")),
        tracer = mockk<Tracer>(relaxed = true),
        smartCache = mockk<SmartCacheStrategy>(relaxed = true),
        indicators = mockk<CachedIndicatorCalculator>(relaxed = true),
        clock = clock,
    )

    private val inst = InstrumentId.fromSymbol("BTC")
    private val margin = MarginMode.fromString("isolated")

    private fun placeOrder() = runBlocking {
        adapter().placeMarketOrderWithTpSl(
            instrumentId = inst,
            side = "buy",
            contracts = BigDecimal("1"),
            tp = BigDecimal("70000"),
            sl = BigDecimal("60000"),
            tickSz = BigDecimal("0.1"),
            clOrdId = "AICID1",
            tag = "ai-signal",
            marginMode = margin,
        )
    }

    // ---- setLeverage mapping ----

    @Test
    fun `setLeverage success maps to Success(true)`() = runBlocking {
        coEvery { rest.setLeverage(any(), any(), any()) } returns OkxCallOutcome.Success(Unit)
        val result = adapter().setLeverage(inst, 5, margin)
        assertTrue(result is TradeResult.Success && result.value)
    }

    @Test
    fun `setLeverage clean rejection maps to Success(false), not a failure`() = runBlocking {
        coEvery { rest.setLeverage(any(), any(), any()) } returns
            OkxCallOutcome.RejectedByExchange("51000", "param error")
        val result = adapter().setLeverage(inst, 5, margin)
        assertTrue(result is TradeResult.Success)
        assertFalse((result as TradeResult.Success).value, "a clean rejection means leverage unchanged -> false")
    }

    @Test
    fun `setLeverage timeout maps to Failure (not a clean false)`() = runBlocking {
        coEvery { rest.setLeverage(any(), any(), any()) } returns OkxCallOutcome.TimeoutUnknown("timed out")
        val result = adapter().setLeverage(inst, 5, margin)
        assertTrue(result is TradeResult.Failure, "timeout must not be swallowed as a clean false")
    }

    @Test
    fun `setLeverage transport error maps to Failure`() = runBlocking {
        coEvery { rest.setLeverage(any(), any(), any()) } returns OkxCallOutcome.TransportError("HTTP 503")
        val result = adapter().setLeverage(inst, 5, margin)
        assertTrue(result is TradeResult.Failure)
    }

    // ---- placeMarketOrderWithTpSl mapping ----

    @Test
    fun `placeOrder success maps to Success with ordId`() {
        coEvery {
            rest.placeMarketOrderWithAttach(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns OkxCallOutcome.Success(OkxPlaceOrderData(ordId = "OID-1", sCode = "0"))

        val result = placeOrder()

        assertTrue(result is TradeResult.Success)
        val outcome = (result as TradeResult.Success).value
        assertTrue(outcome.ok)
        assertEquals("OID-1", outcome.ordId)
    }

    @Test
    fun `placeOrder rejection maps to ApiError with the exchange code`() {
        coEvery {
            rest.placeMarketOrderWithAttach(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns OkxCallOutcome.RejectedByExchange("51008", "insufficient balance")

        val result = placeOrder()

        assertTrue(result is TradeResult.Failure.ApiError)
        assertEquals("51008", (result as TradeResult.Failure.ApiError).code)
    }

    @Test
    fun `placeOrder timeout maps to a distinct TIMEOUT_UNKNOWN failure`() {
        coEvery {
            rest.placeMarketOrderWithAttach(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns OkxCallOutcome.TimeoutUnknown("placement timed out")

        val result = placeOrder()

        assertTrue(result is TradeResult.Failure.ApiError)
        assertEquals("TIMEOUT_UNKNOWN", (result as TradeResult.Failure.ApiError).code)
    }

    @Test
    fun `placeOrder transport error maps to Failure`() {
        coEvery {
            rest.placeMarketOrderWithAttach(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns OkxCallOutcome.TransportError("HTTP 502")

        val result = placeOrder()

        assertTrue(result is TradeResult.Failure)
    }
}

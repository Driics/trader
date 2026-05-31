package ru.driics.aitrade.domain.backtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.domain.backtest.engine.EntryRejection
import ru.driics.aitrade.domain.backtest.engine.InstrumentSpec
import ru.driics.aitrade.domain.backtest.engine.contractsToCoinQty
import ru.driics.aitrade.domain.backtest.engine.sizeEntry
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.services.OrderSizingPolicy
import ru.driics.aitrade.domain.strategy.StrategyDecision
import java.math.BigDecimal

/**
 * Pins the one place the sim re-derives coin quantity ([contractsToCoinQty]) against the contract math
 * inside [OrderSizingPolicy], plus the [sizeEntry] decision (no-stop reject, risk sizing, affordability).
 */
class PositionSizingTest {

    private val policy = OrderSizingPolicy(
        takerFeePct = BigDecimal.ZERO,
        marginBufferPct = BigDecimal.ZERO,
        minLev = 1,
        maxLev = 40,
    )

    private fun roundTrip(coinQty: String, entryPx: String, spec: InstrumentSpec, availableUsd: String): BigDecimal {
        val sized = policy.size(
            OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal(coinQty),
                entryPx = BigDecimal(entryPx),
                ctVal = spec.ctVal,
                ctValCcy = spec.ctValCcy,
                lotSz = spec.lotSz,
                minSz = spec.minSz,
                leverage = 5,
                availableUsd = BigDecimal(availableUsd),
            ),
        )!!
        return contractsToCoinQty(sized.roundedContracts, spec.ctVal, spec.ctValCcy, BigDecimal(entryPx))
    }

    @Test
    fun `usd-quote contract round-trips coin qty exactly`() {
        val spec = InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))
        assertEquals(0, BigDecimal("1").compareTo(roundTrip("1", "100", spec, availableUsd = "100000")))
    }

    @Test
    fun `coin-quote contract (BTC-USDT-SWAP style) round-trips coin qty exactly`() {
        // ctValCcy="BTC" is NOT a USD quote -> takes the *else* branch in both size() and the inverse.
        val spec = InstrumentSpec(BigDecimal("0.01"), "BTC", BigDecimal("0.1"), BigDecimal("0.1"))
        assertEquals(0, BigDecimal("0.5").compareTo(roundTrip("0.5", "50000", spec, availableUsd = "1000000")))
    }

    @Test
    fun `rounding only ever loses less than one lot of coin`() {
        // lotSz=1 contract, ctVal=0.01 BTC/contract -> lot granularity in coin = 0.01.
        val spec = InstrumentSpec(BigDecimal("0.01"), "BTC", BigDecimal("1"), BigDecimal("1"))
        val inverted = roundTrip("0.535", "50000", spec, availableUsd = "1000000")
        assertEquals(0, BigDecimal("0.53").compareTo(inverted)) // 53 contracts * 0.01
        val original = BigDecimal("0.535")
        assertTrue(inverted <= original)
        assertTrue(original - inverted < spec.ctVal * spec.lotSz)
    }

    // ---- sizeEntry ----

    private val usdSpec = InstrumentSpec(BigDecimal("1"), "USDT", BigDecimal("1"), BigDecimal("1"))

    private fun decision(stop: String?, qty: String? = null) = StrategyDecision(
        symbol = "X",
        signal = AiSignal.BUY,
        stopLoss = stop?.let { BigDecimal(it) },
        takeProfit = BigDecimal("120"),
        leverage = 5,
        quantity = qty?.let { BigDecimal(it) },
    )

    @Test
    fun `no stop is rejected on the single NO_STOP path`() {
        val r = sizeEntry(
            decision(stop = null), fillPx = BigDecimal("100"), equity = BigDecimal("10000"),
            availableUsd = BigDecimal("10000"), spec = usdSpec, policy = policy,
            riskPerTradePct = BigDecimal("0.01"), defaultLeverage = 5,
        )
        assertEquals(EntryRejection.NO_STOP, r.rejection)
        assertNull(r.coinQty)
    }

    @Test
    fun `risk sizing yields equity times risk over stop distance, at the fill price`() {
        // riskUsd = 10000 * 0.01 = 100; stopDist = |100 - 98| = 2 -> 50 coin.
        val r = sizeEntry(
            decision(stop = "98"), fillPx = BigDecimal("100"), equity = BigDecimal("10000"),
            availableUsd = BigDecimal("10000"), spec = usdSpec, policy = policy,
            riskPerTradePct = BigDecimal("0.01"), defaultLeverage = 5,
        )
        assertNull(r.rejection)
        assertEquals(0, BigDecimal("50").compareTo(r.coinQty!!))
    }

    @Test
    fun `explicit quantity overrides the risk calc`() {
        val r = sizeEntry(
            decision(stop = "98", qty = "2"), fillPx = BigDecimal("100"), equity = BigDecimal("10000"),
            availableUsd = BigDecimal("10000"), spec = usdSpec, policy = policy,
            riskPerTradePct = BigDecimal("0.01"), defaultLeverage = 5,
        )
        assertNull(r.rejection)
        assertEquals(0, BigDecimal("2").compareTo(r.coinQty!!))
    }

    @Test
    fun `unaffordable size is rejected and counted`() {
        val r = sizeEntry(
            decision(stop = "98"), fillPx = BigDecimal("100"), equity = BigDecimal("10000"),
            availableUsd = BigDecimal("0.0001"), spec = usdSpec, policy = policy,
            riskPerTradePct = BigDecimal("0.01"), defaultLeverage = 5,
        )
        assertEquals(EntryRejection.UNAFFORDABLE, r.rejection)
        assertNull(r.coinQty)
    }

    @Test
    fun `successful size reports the coerced leverage`() {
        val r = sizeEntry(
            decision(stop = "98", qty = "2"), fillPx = BigDecimal("100"), equity = BigDecimal("10000"),
            availableUsd = BigDecimal("10000"), spec = usdSpec, policy = policy,
            riskPerTradePct = BigDecimal("0.01"), defaultLeverage = 5,
        )
        assertNotNull(r.coinQty)
        assertEquals(5, r.leverage)
    }
}

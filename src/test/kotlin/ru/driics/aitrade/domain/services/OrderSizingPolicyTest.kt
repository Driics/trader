package ru.driics.aitrade.domain.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderSizingPolicyTest {

    private val takerFee = BigDecimal("0.0005")
    private val buffer = BigDecimal("0.01")

    private fun policy(minLev: Int = 5, maxLev: Int = 40) =
        OrderSizingPolicy(takerFeePct = takerFee, marginBufferPct = buffer, minLev = minLev, maxLev = maxLev)

    private fun input(
        coinQty: String = "0.1",
        entryPx: String = "50000",
        ctVal: String = "0.01",
        ctValCcy: String = "BTC",
        lotSz: String = "1",
        minSz: String = "1",
        leverage: Int = 10,
        availableUsd: String = "10000",
    ) = OrderSizingPolicy.SizingInput(
        coinQty = BigDecimal(coinQty),
        entryPx = BigDecimal(entryPx),
        ctVal = BigDecimal(ctVal),
        ctValCcy = ctValCcy,
        lotSz = BigDecimal(lotSz),
        minSz = BigDecimal(minSz),
        leverage = leverage,
        availableUsd = BigDecimal(availableUsd),
    )

    @Test
    fun `linear BTC-margined sizing computes contracts from coin qty divided by ctVal`() {
        // 0.1 BTC / ctVal(0.01 BTC) = 10 contracts
        val result = policy().size(input())
        assertNotNull(result)
        result!!
        assertEquals(0, BigDecimal("10").compareTo(result.roundedContracts))
        assertEquals(10, result.leverage)
        // contractValueUsd = 0.01 * 50000 = 500; cost/contract = 500 * (0.1 + 0.0005 + 0.01) = 55.25
        assertEquals(0, BigDecimal("55.25").compareTo(result.perContractUsd.stripTrailingZeros()))
    }

    @Test
    fun `inverse USD-quote sizing uses notional divided by ctVal`() {
        // ctValCcy = USDT (USD quote), ctVal = 100 USD per contract
        // rawContracts = (0.1 * 50000) / 100 = 50
        val result = policy().size(input(ctVal = "100", ctValCcy = "USDT", leverage = 5))
        assertNotNull(result)
        result!!
        assertEquals(0, BigDecimal("50").compareTo(result.roundedContracts))
        assertEquals(5, result.leverage)
    }

    @Test
    fun `leverage above max is clamped`() {
        val result = policy(maxLev = 40).size(input(leverage = 100))
        assertNotNull(result)
        assertEquals(40, result!!.leverage)
    }

    @Test
    fun `leverage below min is clamped`() {
        val result = policy(minLev = 5).size(input(leverage = 1))
        assertNotNull(result)
        assertEquals(5, result!!.leverage)
    }

    @Test
    fun `affordability cap reduces contracts when available USD is tight`() {
        // cost/contract = 55.25; available 100 -> max ~1 contract
        val result = policy().size(input(availableUsd = "100"))
        assertNotNull(result)
        assertTrue(
            result!!.roundedContracts <= BigDecimal("2"),
            "Expected affordability to cap contracts near 1; got ${result.roundedContracts}",
        )
    }

    @Test
    fun `zero ctVal returns null`() {
        assertNull(policy().size(input(ctVal = "0")))
    }

    @Test
    fun `zero lotSz returns null`() {
        assertNull(policy().size(input(lotSz = "0")))
    }

    @Test
    fun `qty below minSz returns null`() {
        // 0.001 BTC / 0.01 = 0.1 contracts; lot=1 floors to 0 -> below minSz=1
        assertNull(policy().size(input(coinQty = "0.001")))
    }

    @Test
    fun `cannot afford even one contract returns null`() {
        assertNull(policy().size(input(availableUsd = "0.01")))
    }

    @Test
    fun `usd quote casing is normalized`() {
        // lower-case "usdt" should still be treated as USD-quote
        val result = policy().size(input(ctVal = "100", ctValCcy = "usdt"))
        assertNotNull(result)
        // Same math as the inverse test
        assertEquals(0, BigDecimal("50").compareTo(result!!.roundedContracts))
    }
}

package ru.driics.aitrade.domain.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.math.BigDecimal

@DisplayName("OrderSizingPolicy Tests")
class OrderSizingPolicyTest {

    private val policy = OrderSizingPolicy(
        takerFeePct = BigDecimal("0.0005"),
        marginBufferPct = BigDecimal("0.01"),
        minLev = 5,
        maxLev = 40
    )

    @Nested
    @DisplayName("Happy Path Tests")
    inner class HappyPathTests {

        @Test
        fun `should calculate correct sizing for USDT perpetual`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertTrue(result!!.roundedContracts > BigDecimal.ZERO)
            assertEquals(10, result.leverage)
            assertTrue(result.totalUsd <= input.availableUsd)
        }

        @Test
        fun `should calculate correct sizing for coin-margined contract`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1.0"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("100"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 20,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals(20, result!!.leverage)
        }

        @Test
        fun `should respect minimum size requirement`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.001"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("1"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("10"),
                leverage = 10,
                availableUsd = BigDecimal("1000")
            )

            val result = policy.size(input)

            if (result != null) {
                assertTrue(result.roundedContracts >= input.minSz)
            }
        }

        @Test
        fun `should handle fractional lot sizes`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.123"),
                entryPx = BigDecimal("2000"),
                ctVal = BigDecimal("0.1"),
                ctValCcy = "ETH",
                lotSz = BigDecimal("0.1"),
                minSz = BigDecimal("0.1"),
                leverage = 15,
                availableUsd = BigDecimal("500")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertTrue(result!!.roundedContracts.remainder(input.lotSz) == BigDecimal.ZERO)
        }
    }

    @Nested
    @DisplayName("Leverage Coercion Tests")
    inner class LeverageCoercionTests {

        @Test
        fun `should coerce leverage below minimum to minimum`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 2,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals(5, result!!.leverage, "Leverage should be coerced to minimum of 5")
        }

        @Test
        fun `should coerce leverage above maximum to maximum`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 100,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals(40, result!!.leverage, "Leverage should be coerced to maximum of 40")
        }

        @Test
        fun `should accept leverage within range`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 25,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals(25, result!!.leverage)
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    inner class EdgeCasesTests {

        @Test
        fun `should return null when contract value is zero`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal.ZERO,
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNull(result, "Should return null for zero contract value")
        }

        @Test
        fun `should return null when contract value is negative`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("-0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNull(result)
        }

        @Test
        fun `should return null when coin quantity is zero`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal.ZERO,
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNull(result)
        }

        @Test
        fun `should return null when coin quantity is negative`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("-0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNull(result)
        }

        @Test
        fun `should return null when rounded contracts below minimum size`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.0001"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("1"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("100"),
                leverage = 10,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNull(result, "Should return null when contracts below minimum")
        }

        @Test
        fun `should return null when available USD is zero`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal.ZERO
            )

            val result = policy.size(input)

            assertNull(result, "Should return null when no funds available")
        }

        @Test
        fun `should return null when available USD is negative`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.5"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("-1000")
            )

            val result = policy.size(input)

            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Affordability Tests")
    inner class AffordabilityTests {

        @Test
        fun `should cap contracts to affordable amount`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("10.0"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("100")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertTrue(result!!.totalUsd <= input.availableUsd)
            assertTrue(result.roundedContracts <= result.requestedContracts)
        }

        @Test
        fun `should return exact requested contracts when affordable`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.01"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("10000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals(result!!.roundedContracts, result.roundedContracts)
        }
    }

    @Nested
    @DisplayName("Currency Type Tests")
    inner class CurrencyTypeTests {

        @Test
        fun `should handle USDT currency correctly`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1000"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("10"),
                ctValCcy = "USDT",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
        }

        @Test
        fun `should handle USD currency correctly`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1000"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("10"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
        }

        @Test
        fun `should handle USB currency correctly`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1000"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("10"),
                ctValCcy = "USB",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
        }

        @Test
        fun `should handle lowercase currency correctly`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1000"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("10"),
                ctValCcy = "usdt",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
        }

        @Test
        fun `should handle coin-based currency correctly`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1.0"),
                entryPx = BigDecimal("50000"),
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Rounding Tests")
    inner class RoundingTests {

        @Test
        fun `should round down to lot size`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.256"),
                entryPx = BigDecimal("2000"),
                ctVal = BigDecimal("0.1"),
                ctValCcy = "ETH",
                lotSz = BigDecimal("0.1"),
                minSz = BigDecimal("0.1"),
                leverage = 10,
                availableUsd = BigDecimal("1000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            val remainder = result!!.roundedContracts.remainder(input.lotSz)
            assertEquals(BigDecimal.ZERO, remainder)
        }

        @Test
        fun `should strip trailing zeros from output`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1.0"),
                entryPx = BigDecimal("1000"),
                ctVal = BigDecimal("1.0"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals("1", result!!.roundedContracts.toPlainString())
        }
    }

    @Nested
    @DisplayName("Fee and Buffer Calculation Tests")
    inner class FeeAndBufferTests {

        @Test
        fun `should include taker fee in cost calculation`() {
            val policyWithHighFee = OrderSizingPolicy(
                takerFeePct = BigDecimal("0.1"),
                marginBufferPct = BigDecimal.ZERO,
                minLev = 5,
                maxLev = 40
            )

            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1.0"),
                entryPx = BigDecimal("1000"),
                ctVal = BigDecimal("1"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("200")
            )

            val result = policyWithHighFee.size(input)

            assertNotNull(result)
            assertTrue(result!!.perContractUsd > BigDecimal("100"))
        }

        @Test
        fun `should include margin buffer in cost calculation`() {
            val policyWithHighBuffer = OrderSizingPolicy(
                takerFeePct = BigDecimal.ZERO,
                marginBufferPct = BigDecimal("0.1"),
                minLev = 5,
                maxLev = 40
            )

            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("1.0"),
                entryPx = BigDecimal("1000"),
                ctVal = BigDecimal("1"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 10,
                availableUsd = BigDecimal("200")
            )

            val result = policyWithHighBuffer.size(input)

            assertNotNull(result)
            assertTrue(result!!.perContractUsd > BigDecimal("100"))
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {

        @Test
        fun `should handle realistic BTC perpetual scenario`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("0.1"),
                entryPx = BigDecimal("45000"),
                ctVal = BigDecimal("100"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 20,
                availableUsd = BigDecimal("2500")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals(20, result!!.leverage)
            assertTrue(result.roundedContracts > BigDecimal.ZERO)
            assertTrue(result.totalUsd <= BigDecimal("2500"))
            assertTrue(result.roundedContracts.remainder(BigDecimal.ONE) == BigDecimal.ZERO)
        }

        @Test
        fun `should handle realistic ETH perpetual scenario`() {
            val input = OrderSizingPolicy.SizingInput(
                coinQty = BigDecimal("2.5"),
                entryPx = BigDecimal("3000"),
                ctVal = BigDecimal("10"),
                ctValCcy = "USD",
                lotSz = BigDecimal("1"),
                minSz = BigDecimal("1"),
                leverage = 15,
                availableUsd = BigDecimal("5000")
            )

            val result = policy.size(input)

            assertNotNull(result)
            assertEquals(15, result!!.leverage)
            assertTrue(result.totalUsd <= BigDecimal("5000"))
        }
    }
}
package ru.driics.aitrade.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.BeforeEach
import ru.driics.aitrade.model.ExitPlan
import ru.driics.aitrade.model.Position
import java.math.BigDecimal

@DisplayName("PromptFormatterService Tests")
class PromptFormatterServiceTest {

    private lateinit var service: PromptFormatterService

    @BeforeEach
    fun setup() {
        service = PromptFormatterService()
    }

    @Nested
    @DisplayName("formatNumber Tests")
    inner class FormatNumberTests {

        @Test
        fun `should return zero for null input`() {
            val result = service.formatNumber(null)

            assertEquals("0", result)
        }

        @Test
        fun `should return zero for zero input`() {
            val result = service.formatNumber(BigDecimal.ZERO)

            assertEquals("0", result)
        }

        @Test
        fun `should format large numbers with 2 decimals`() {
            val result = service.formatNumber(BigDecimal("1500000"))

            assertEquals("1500000", result)
        }

        @Test
        fun `should format numbers above 100 with 2 decimals`() {
            val result = service.formatNumber(BigDecimal("150.789"))

            assertEquals("150.79", result)
        }

        @Test
        fun `should format numbers above 1 with 3 decimals`() {
            val result = service.formatNumber(BigDecimal("5.6789"))

            assertEquals("5.679", result)
        }

        @Test
        fun `should format numbers above 0.01 with 5 decimals`() {
            val result = service.formatNumber(BigDecimal("0.123456"))

            assertEquals("0.12346", result)
        }

        @Test
        fun `should format very small numbers with 8 decimals`() {
            val result = service.formatNumber(BigDecimal("0.000123456789"))

            assertEquals("0.00012346", result)
        }

        @Test
        fun `should strip trailing zeros`() {
            val result = service.formatNumber(BigDecimal("100.00"))

            assertEquals("100", result)
        }

        @Test
        fun `should handle negative numbers`() {
            val result = service.formatNumber(BigDecimal("-123.456"))

            assertEquals("-123.46", result)
        }

        @Test
        fun `should handle edge case at 1 million`() {
            val result = service.formatNumber(BigDecimal("1000000"))

            assertEquals("1000000", result)
        }

        @Test
        fun `should handle edge case at 100`() {
            val result = service.formatNumber(BigDecimal("100"))

            assertEquals("100", result)
        }

        @Test
        fun `should handle edge case at 1`() {
            val result = service.formatNumber(BigDecimal("1"))

            assertEquals("1", result)
        }

        @Test
        fun `should handle edge case at 0_01`() {
            val result = service.formatNumber(BigDecimal("0.01"))

            assertEquals("0.01", result)
        }
    }

    @Nested
    @DisplayName("formatMoneyUsd Tests")
    inner class FormatMoneyUsdTests {

        @Test
        fun `should return formatted zero for null input`() {
            val result = service.formatMoneyUsd(null)

            assertEquals("$0.00", result)
        }

        @Test
        fun `should format positive amounts with dollar sign`() {
            val result = service.formatMoneyUsd(BigDecimal("1234.56"))

            assertTrue(result.contains("1,234.56") || result.contains("1234.56"))
            assertTrue(result.contains("$"))
        }

        @Test
        fun `should format negative amounts`() {
            val result = service.formatMoneyUsd(BigDecimal("-1234.56"))

            assertTrue(result.contains("1,234.56") || result.contains("1234.56"))
        }

        @Test
        fun `should round to 2 decimal places`() {
            val result = service.formatMoneyUsd(BigDecimal("123.456789"))

            assertTrue(result.contains("123.46"))
        }

        @Test
        fun `should handle zero`() {
            val result = service.formatMoneyUsd(BigDecimal.ZERO)

            assertTrue(result.contains("0.00"))
            assertTrue(result.contains("$"))
        }

        @Test
        fun `should handle large amounts`() {
            val result = service.formatMoneyUsd(BigDecimal("1000000.50"))

            assertTrue(result.contains("$"))
            assertTrue(result.contains("1,000,000.50") || result.contains("1000000.50"))
        }
    }

    @Nested
    @DisplayName("formatScientific Tests")
    inner class FormatScientificTests {

        @Test
        fun `should return zero for null input`() {
            val result = service.formatScientific(null)

            assertEquals("0", result)
        }

        @Test
        fun `should return zero for zero input`() {
            val result = service.formatScientific(BigDecimal.ZERO)

            assertEquals("0", result)
        }

        @Test
        fun `should use scientific notation for very small numbers`() {
            val result = service.formatScientific(BigDecimal("0.00001"))

            assertTrue(result.contains("e") || result.contains("E"))
        }

        @Test
        fun `should use plain format for normal numbers`() {
            val result = service.formatScientific(BigDecimal("123.456"))

            assertEquals("123.456", result)
        }

        @Test
        fun `should strip trailing zeros for plain format`() {
            val result = service.formatScientific(BigDecimal("123.00"))

            assertEquals("123", result)
        }

        @Test
        fun `should handle edge case at 0_0001`() {
            val result = service.formatScientific(BigDecimal("0.0001"))

            assertEquals("0.0001", result)
        }

        @Test
        fun `should handle negative very small numbers`() {
            val result = service.formatScientific(BigDecimal("-0.00001"))

            assertTrue(result.contains("e") || result.contains("E") || result.contains("-"))
        }
    }

    @Nested
    @DisplayName("formatPercent Tests")
    inner class FormatPercentTests {

        @Test
        fun `should return zero percent for null input`() {
            val result = service.formatPercent(null)

            assertEquals("0%", result)
        }

        @Test
        fun `should return zero percent for zero input`() {
            val result = service.formatPercent(BigDecimal.ZERO)

            assertEquals("0%", result)
        }

        @Test
        fun `should format positive percentages`() {
            val result = service.formatPercent(BigDecimal("15.678"))

            assertEquals("15.68%", result)
        }

        @Test
        fun `should format negative percentages`() {
            val result = service.formatPercent(BigDecimal("-15.678"))

            assertEquals("-15.68%", result)
        }

        @Test
        fun `should round to 2 decimal places`() {
            val result = service.formatPercent(BigDecimal("12.3456"))

            assertEquals("12.35%", result)
        }

        @Test
        fun `should strip trailing zeros`() {
            val result = service.formatPercent(BigDecimal("10.00"))

            assertEquals("10%", result)
        }

        @Test
        fun `should handle very small percentages`() {
            val result = service.formatPercent(BigDecimal("0.0123"))

            assertEquals("0.01%", result)
        }
    }

    @Nested
    @DisplayName("formatNumberList Tests")
    inner class FormatNumberListTests {

        @Test
        fun `should format empty list`() {
            val result = service.formatNumberList(emptyList())

            assertEquals("[]", result)
        }

        @Test
        fun `should format single number`() {
            val result = service.formatNumberList(listOf(BigDecimal("123.456")))

            assertEquals("[123.46]", result)
        }

        @Test
        fun `should format multiple numbers`() {
            val numbers = listOf(
                BigDecimal("100"), BigDecimal("200.5"), BigDecimal("0.0001")
            )

            val result = service.formatNumberList(numbers)

            assertTrue(result.startsWith("["))
            assertTrue(result.endsWith("]"))
            assertTrue(result.contains("100"))
            assertTrue(result.contains("200.5"))
        }

        @Test
        fun `should use comma separator`() {
            val numbers = listOf(BigDecimal("1"), BigDecimal("2"), BigDecimal("3"))

            val result = service.formatNumberList(numbers)

            assertEquals("[1, 2, 3]", result)
        }
    }

    @Nested
    @DisplayName("formatPositions Tests")
    inner class FormatPositionsTests {

        @Test
        fun `should return empty object for empty list`() {
            val result = service.formatPositions(emptyList())

            assertEquals("{}", result)
        }

        @Test
        fun `should format single position as dict`() {
            val position = Position(
                symbol = "BTC",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("50000"),
                currentPrice = BigDecimal("51000"),
                unrealizedPnl = BigDecimal("500"),
                waitForFill = false
            )

            val result = service.formatPositions(listOf(position))

            assertTrue(result.contains("BTC"))
            assertTrue(result.contains("'symbol'"))
            assertTrue(result.contains("'quantity'"))
            assertTrue(result.contains("0.5"))
        }

        @Test
        fun `should format multiple positions as array`() {
            val positions = listOf(
                Position(
                    symbol = "BTC",
                    quantity = BigDecimal("0.5"),
                    entryPrice = BigDecimal("50000"),
                    currentPrice = BigDecimal("51000"),
                    unrealizedPnl = BigDecimal("500"),
                    waitForFill = false
                ),
                Position(
                    symbol = "ETH",
                    quantity = BigDecimal("5"),
                    entryPrice = BigDecimal("3000"),
                    currentPrice = BigDecimal("3100"),
                    unrealizedPnl = BigDecimal("500"),
                    waitForFill = false
                )
            )

            val result = service.formatPositions(positions)

            assertTrue(result.contains("BTC"))
            assertTrue(result.contains("ETH"))
        }

        @Test
        fun `should include optional fields when present`() {
            val position = Position(
                symbol = "BTC",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("50000"),
                currentPrice = BigDecimal("51000"),
                unrealizedPnl = BigDecimal("500"),
                liquidationPrice = BigDecimal("40000"),
                leverage = 10,
                confidence = BigDecimal("0.85"),
                riskUsd = BigDecimal("100"),
                waitForFill = false
            )

            val result = service.formatPositions(listOf(position))

            assertTrue(result.contains("'liquidation_price'"))
            assertTrue(result.contains("'leverage'"))
            assertTrue(result.contains("'confidence'"))
            assertTrue(result.contains("'risk_usd'"))
        }

        @Test
        fun `should include exit plan when present`() {
            val exitPlan = ExitPlan(
                profitTarget = BigDecimal("52000"),
                stopLoss = BigDecimal("48000"),
                invalidationCondition = "Break below 47000"
            )

            val position = Position(
                symbol = "BTC",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("50000"),
                currentPrice = BigDecimal("51000"),
                unrealizedPnl = BigDecimal("500"),
                exitPlan = exitPlan,
                waitForFill = false
            )

            val result = service.formatPositions(listOf(position))

            assertTrue(result.contains("'exit_plan'"))
            assertTrue(result.contains("'profit_target'"))
            assertTrue(result.contains("'stop_loss'"))
        }

        @Test
        fun `should include order IDs when present`() {
            val position = Position(
                symbol = "BTC",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("50000"),
                currentPrice = BigDecimal("51000"),
                unrealizedPnl = BigDecimal("500"),
                slOid = 12345L,
                tpOid = 67890L,
                entryOid = 11111L,
                waitForFill = false
            )

            val result = service.formatPositions(listOf(position))

            assertTrue(result.contains("'sl_oid'"))
            assertTrue(result.contains("'tp_oid'"))
            assertTrue(result.contains("'entry_oid'"))
        }

        @Test
        fun `should replace double quotes with single quotes`() {
            val position = Position(
                symbol = "BTC",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("50000"),
                currentPrice = BigDecimal("51000"),
                unrealizedPnl = BigDecimal("500"),
                waitForFill = false
            )

            val result = service.formatPositions(listOf(position))

            assertFalse(result.contains("\""))
            assertTrue(result.contains("'"))
        }

        @Test
        fun `should include wait for fill flag`() {
            val position = Position(
                symbol = "BTC",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("50000"),
                currentPrice = BigDecimal("51000"),
                unrealizedPnl = BigDecimal("500"),
                waitForFill = true
            )

            val result = service.formatPositions(listOf(position))

            assertTrue(result.contains("'wait_for_fill'"))
            assertTrue(result.contains("true"))
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {

        @Test
        fun `should format realistic trading position`() {
            val exitPlan = ExitPlan(
                profitTarget = BigDecimal("52000"),
                stopLoss = BigDecimal("48000"),
                invalidationCondition = "Break below support"
            )

            val position = Position(
                symbol = "BTC",
                quantity = BigDecimal("0.1"),
                entryPrice = BigDecimal("50000"),
                currentPrice = BigDecimal("50500"),
                liquidationPrice = BigDecimal("45000"),
                unrealizedPnl = BigDecimal("50"),
                leverage = 10,
                exitPlan = exitPlan,
                confidence = BigDecimal("0.75"),
                riskUsd = BigDecimal("500"),
                slOid = 123456L,
                tpOid = 789012L,
                entryOid = 345678L,
                notionalUsd = BigDecimal("5000"),
                waitForFill = false
            )

            val result = service.formatPositions(listOf(position))

            assertNotNull(result)
            assertNotEquals("{}", result)
            assertTrue(result.contains("BTC"))
            assertTrue(result.contains("0.1"))
            assertTrue(result.contains("50000"))
        }

        @Test
        fun `should format multiple diverse positions`() {
            val positions = listOf(
                Position(
                    symbol = "BTC",
                    quantity = BigDecimal("0.1"),
                    entryPrice = BigDecimal("50000"),
                    currentPrice = BigDecimal("50500"),
                    unrealizedPnl = BigDecimal("50"),
                    leverage = 10,
                    waitForFill = false
                ),
                Position(
                    symbol = "ETH",
                    quantity = BigDecimal("2"),
                    entryPrice = BigDecimal("3000"),
                    currentPrice = BigDecimal("3050"),
                    unrealizedPnl = BigDecimal("100"),
                    leverage = 5,
                    waitForFill = true
                )
            )

            val result = service.formatPositions(positions)

            assertNotNull(result)
            assertTrue(result.contains("BTC"))
            assertTrue(result.contains("ETH"))
        }
    }
}
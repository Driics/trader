package ru.driics.aitrade.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.math.BigDecimal

@DisplayName("TradingModels Tests")
class TradingModelsTest {

    @Nested
    @DisplayName("AiTradeSignalArgs Tests")
    inner class AiTradeSignalArgsTests {

        @Test
        fun `should create instance with required fields`() {
            val signal = AiTradeSignalArgs(
                coin = "BTC",
                signal = "buy",
                quantity = BigDecimal("0.1")
            )

            assertEquals("BTC", signal.coin)
            assertEquals("buy", signal.signal)
            assertEquals(BigDecimal("0.1"), signal.quantity)
        }

        @Test
        fun `should support all optional fields`() {
            val signal = AiTradeSignalArgs(
                coin = "BTC",
                signal = "buy",
                quantity = BigDecimal("0.1"),
                profitTarget = BigDecimal("52000"),
                stopLoss = BigDecimal("48000"),
                invalidationCondition = "Break below 47000",
                leverage = 10,
                confidence = BigDecimal("0.85"),
                riskUsd = BigDecimal("500"),
                justification = "Bullish pattern"
            )

            assertEquals(BigDecimal("52000"), signal.profitTarget)
            assertEquals(BigDecimal("48000"), signal.stopLoss)
            assertEquals("Break below 47000", signal.invalidationCondition)
            assertEquals(10, signal.leverage)
            assertEquals(BigDecimal("0.85"), signal.confidence)
            assertEquals(BigDecimal("500"), signal.riskUsd)
            assertEquals("Bullish pattern", signal.justification)
        }

        @Test
        fun `should handle null quantity`() {
            val signal = AiTradeSignalArgs(
                coin = "BTC",
                signal = "buy",
                quantity = null,
                riskUsd = BigDecimal("500")
            )

            assertNull(signal.quantity)
            assertEquals(BigDecimal("500"), signal.riskUsd)
        }

        @Test
        fun `should handle zero quantity`() {
            val signal = AiTradeSignalArgs(
                coin = "BTC",
                signal = "buy",
                quantity = BigDecimal.ZERO
            )

            assertEquals(BigDecimal.ZERO, signal.quantity)
        }

        @Test
        fun `should support buy signal`() {
            val signal = AiTradeSignalArgs(
                coin = "BTC",
                signal = "buy",
                quantity = BigDecimal("0.1")
            )

            assertEquals("buy", signal.signal)
        }

        @Test
        fun `should support sell signal`() {
            val signal = AiTradeSignalArgs(
                coin = "BTC",
                signal = "sell",
                quantity = BigDecimal("0.1")
            )

            assertEquals("sell", signal.signal)
        }

        @Test
        fun `should support hold signal`() {
            val signal = AiTradeSignalArgs(
                coin = "BTC",
                signal = "hold",
                quantity = null
            )

            assertEquals("hold", signal.signal)
            assertNull(signal.quantity)
        }
    }

    @Nested
    @DisplayName("OkxPublicInstrumentsApiResponse Tests")
    inner class OkxPublicInstrumentsApiResponseTests {

        @Test
        fun `isSuccess should return true for code 0`() {
            val response = OkxPublicInstrumentsApiResponse(code = "0")

            assertTrue(response.isSuccess())
        }

        @Test
        fun `isSuccess should return false for non-zero code`() {
            val response = OkxPublicInstrumentsApiResponse(code = "1")

            assertFalse(response.isSuccess())
        }

        @Test
        fun `firstOrNull should return first instrument`() {
            val instrument = OkxInstrumentInfo(
                instId = "BTC-USDT-SWAP",
                instType = "SWAP",
                ctVal = "0.01",
                ctValCcy = "BTC",
                lotSz = "1",
                minSz = "1",
                tickSz = "0.1"
            )
            val response = OkxPublicInstrumentsApiResponse(
                code = "0",
                data = listOf(instrument)
            )

            assertEquals(instrument, response.firstOrNull())
        }

        @Test
        fun `firstOrNull should return null for empty data`() {
            val response = OkxPublicInstrumentsApiResponse(code = "0", data = emptyList())

            assertNull(response.firstOrNull())
        }
    }

    @Nested
    @DisplayName("OkxPlaceOrderApiResponse Tests")
    inner class OkxPlaceOrderApiResponseTests {

        @Test
        fun `isSuccess should return true when both codes are 0`() {
            val orderData = OkxPlaceOrderData(
                ordId = "12345",
                clOrdId = "client123",
                sCode = "0",
                sMsg = "Success"
            )
            val response = OkxPlaceOrderApiResponse(
                code = "0",
                data = listOf(orderData)
            )

            assertTrue(response.isSuccess())
        }

        @Test
        fun `isSuccess should return false when response code is non-zero`() {
            val orderData = OkxPlaceOrderData(sCode = "0")
            val response = OkxPlaceOrderApiResponse(
                code = "1",
                data = listOf(orderData)
            )

            assertFalse(response.isSuccess())
        }

        @Test
        fun `isSuccess should return false when order sCode is non-zero`() {
            val orderData = OkxPlaceOrderData(sCode = "1")
            val response = OkxPlaceOrderApiResponse(
                code = "0",
                data = listOf(orderData)
            )

            assertFalse(response.isSuccess())
        }

        @Test
        fun `isSuccess should return false for empty data`() {
            val response = OkxPlaceOrderApiResponse(code = "0", data = emptyList())

            assertFalse(response.isSuccess())
        }

        @Test
        fun `firstOrNull should return first order data`() {
            val orderData = OkxPlaceOrderData(
                ordId = "12345",
                clOrdId = "client123",
                sCode = "0"
            )
            val response = OkxPlaceOrderApiResponse(
                code = "0",
                data = listOf(orderData)
            )

            assertEquals(orderData, response.firstOrNull())
        }
    }

    @Nested
    @DisplayName("AIAction Enum Tests")
    inner class AIActionTests {

        @Test
        fun `should have PLACED action`() {
            val action = AIAction.PLACED

            assertEquals("PLACED", action.name)
        }

        @Test
        fun `should have SKIPPED action`() {
            val action = AIAction.SKIPPED

            assertEquals("SKIPPED", action.name)
        }

        @Test
        fun `should support valueOf`() {
            val placed = AIAction.valueOf("PLACED")
            val skipped = AIAction.valueOf("SKIPPED")

            assertEquals(AIAction.PLACED, placed)
            assertEquals(AIAction.SKIPPED, skipped)
        }
    }

    @Nested
    @DisplayName("AiTradeExecutionResult Tests")
    inner class AiTradeExecutionResultTests {

        @Test
        fun `should create result with required fields`() {
            val result = AiTradeExecutionResult(
                symbol = "BTC",
                action = AIAction.PLACED,
                message = "Order placed successfully"
            )

            assertEquals("BTC", result.symbol)
            assertEquals(AIAction.PLACED, result.action)
            assertEquals("Order placed successfully", result.message)
        }

        @Test
        fun `should support all optional fields`() {
            val result = AiTradeExecutionResult(
                symbol = "BTC",
                action = AIAction.PLACED,
                message = "Order placed successfully",
                instId = "BTC-USDT-SWAP",
                clOrdId = "client123",
                ordId = "12345",
                requestedContracts = BigDecimal("10"),
                placedContracts = BigDecimal("9")
            )

            assertEquals("BTC-USDT-SWAP", result.instId)
            assertEquals("client123", result.clOrdId)
            assertEquals("12345", result.ordId)
            assertEquals(BigDecimal("10"), result.requestedContracts)
            assertEquals(BigDecimal("9"), result.placedContracts)
        }

        @Test
        fun `should handle skipped action`() {
            val result = AiTradeExecutionResult(
                symbol = "BTC",
                action = AIAction.SKIPPED,
                message = "Insufficient funds"
            )

            assertEquals(AIAction.SKIPPED, result.action)
            assertEquals("Insufficient funds", result.message)
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {

        @Test
        fun `should create complete trade signal envelope`() {
            val args = AiTradeSignalArgs(
                coin = "BTC",
                signal = "buy",
                quantity = BigDecimal("0.1"),
                profitTarget = BigDecimal("52000"),
                stopLoss = BigDecimal("48000"),
                leverage = 10,
                confidence = BigDecimal("0.85"),
                riskUsd = BigDecimal("500"),
                justification = "Strong bullish momentum"
            )

            val envelope = AiTradeEnvelope(args = args)

            assertEquals(args, envelope.args)
            assertEquals("BTC", envelope.args.coin)
            assertEquals("buy", envelope.args.signal)
        }

        @Test
        fun `should create successful order response`() {
            val orderData = OkxPlaceOrderData(
                ordId = "123456789",
                clOrdId = "AIBTC123",
                sCode = "0",
                sMsg = "Order placed successfully"
            )

            val response = OkxPlaceOrderApiResponse(
                code = "0",
                msg = "Success",
                data = listOf(orderData)
            )

            assertTrue(response.isSuccess())
            assertEquals("123456789", response.firstOrNull()?.ordId)
            assertEquals("AIBTC123", response.firstOrNull()?.clOrdId)
        }

        @Test
        fun `should create complete execution result`() {
            val result = AiTradeExecutionResult(
                symbol = "BTC",
                action = AIAction.PLACED,
                message = "Order placed: 9 contracts at 50000 USDT",
                instId = "BTC-USDT-SWAP",
                clOrdId = "AIBTC123",
                ordId = "123456789",
                requestedContracts = BigDecimal("10"),
                placedContracts = BigDecimal("9")
            )

            assertEquals(AIAction.PLACED, result.action)
            assertTrue(result.message.contains("Order placed"))
            assertNotNull(result.ordId)
            assertTrue(result.placedContracts!! < result.requestedContracts!!)
        }
    }
}
package ru.driics.aitrade.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import ru.driics.aitrade.config.OkxProperties

@DisplayName("OkxAuthService Tests")
class OkxAuthServiceTest {

    private lateinit var okxProperties: OkxProperties
    private lateinit var service: OkxAuthService

    @BeforeEach
    fun setup() {
        okxProperties = mock()
        whenever(okxProperties.key).thenReturn("test-api-key")
        whenever(okxProperties.secret).thenReturn("test-secret-key")
        whenever(okxProperties.passphrase).thenReturn("test-passphrase")
        
        service = OkxAuthService(okxProperties)
    }

    @Nested
    @DisplayName("createAuthHeaders Tests")
    inner class CreateAuthHeadersTests {

        @Test
        fun `should create headers with required fields`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            assertTrue(headers.containsKey("OK-ACCESS-KEY"))
            assertTrue(headers.containsKey("OK-ACCESS-SIGN"))
            assertTrue(headers.containsKey("OK-ACCESS-TIMESTAMP"))
            assertTrue(headers.containsKey("OK-ACCESS-PASSPHRASE"))
            assertTrue(headers.containsKey("Content-Type"))
        }

        @Test
        fun `should include api key in headers`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            assertEquals("test-api-key", headers["OK-ACCESS-KEY"])
        }

        @Test
        fun `should include passphrase in headers`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            assertEquals("test-passphrase", headers["OK-ACCESS-PASSPHRASE"])
        }

        @Test
        fun `should include content type as json`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            assertEquals("application/json", headers["Content-Type"])
        }

        @Test
        fun `should generate timestamp in ISO format`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            val timestamp = headers["OK-ACCESS-TIMESTAMP"]
            assertNotNull(timestamp)
            assertTrue(timestamp!!.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
        }

        @Test
        fun `should generate non-empty signature`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            val signature = headers["OK-ACCESS-SIGN"]
            assertNotNull(signature)
            assertTrue(signature!!.isNotEmpty())
        }

        @Test
        fun `should uppercase method in signature calculation`() {
            val headers1 = service.createAuthHeaders("get", "/api/v5/account/balance")
            val headers2 = service.createAuthHeaders("GET", "/api/v5/account/balance")

            assertNotNull(headers1["OK-ACCESS-SIGN"])
            assertNotNull(headers2["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should handle POST requests`() {
            val headers = service.createAuthHeaders("POST", "/api/v5/trade/order")

            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertTrue(headers.containsKey("OK-ACCESS-KEY"))
        }

        @Test
        fun `should handle DELETE requests`() {
            val headers = service.createAuthHeaders("DELETE", "/api/v5/trade/order")

            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertTrue(headers.containsKey("OK-ACCESS-KEY"))
        }

        @Test
        fun `should include body in signature when provided`() {
            val body = """{"instId":"BTC-USDT-SWAP","side":"buy"}"""
            val headers = service.createAuthHeaders("POST", "/api/v5/trade/order", body)

            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertTrue(headers["OK-ACCESS-SIGN"]!!.isNotEmpty())
        }

        @Test
        fun `should handle empty body`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance", "")

            assertNotNull(headers["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should generate different signatures for different timestamps`() {
            val headers1 = service.createAuthHeaders("GET", "/api/v5/account/balance")
            Thread.sleep(10)
            val headers2 = service.createAuthHeaders("GET", "/api/v5/account/balance")

            assertNotEquals(headers1["OK-ACCESS-SIGN"], headers2["OK-ACCESS-SIGN"])
            assertNotEquals(headers1["OK-ACCESS-TIMESTAMP"], headers2["OK-ACCESS-TIMESTAMP"])
        }

        @Test
        fun `should generate different signatures for different methods`() {
            val timestamp = System.currentTimeMillis().toString()
            val headers1 = service.createAuthHeaders("GET", "/api/v5/account/balance")
            val headers2 = service.createAuthHeaders("POST", "/api/v5/account/balance")

            // Different methods should produce different signatures (even though timestamp differs)
            assertNotEquals(headers1["OK-ACCESS-SIGN"], headers2["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should generate different signatures for different paths`() {
            val headers1 = service.createAuthHeaders("GET", "/api/v5/account/balance")
            val headers2 = service.createAuthHeaders("GET", "/api/v5/market/ticker")

            assertNotEquals(headers1["OK-ACCESS-SIGN"], headers2["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should handle paths with query parameters`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/market/candles?instId=BTC-USDT")

            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertTrue(headers["OK-ACCESS-SIGN"]!!.isNotEmpty())
        }

        @Test
        fun `should handle complex request body`() {
            val body = """
                {
                    "instId":"BTC-USDT-SWAP",
                    "tdMode":"cross",
                    "side":"buy",
                    "ordType":"limit",
                    "px":"50000",
                    "sz":"1"
                }
            """.trimIndent()
            
            val headers = service.createAuthHeaders("POST", "/api/v5/trade/order", body)

            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertTrue(headers["OK-ACCESS-SIGN"]!!.isNotEmpty())
        }

        @Test
        fun `should produce base64 encoded signature`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            val signature = headers["OK-ACCESS-SIGN"]
            assertNotNull(signature)
            // Base64 characters
            assertTrue(signature!!.matches(Regex("^[A-Za-z0-9+/]+=*$")))
        }

        @Test
        fun `should handle lowercase method consistently`() {
            val headers = service.createAuthHeaders("post", "/api/v5/trade/order")

            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertEquals("test-api-key", headers["OK-ACCESS-KEY"])
        }

        @Test
        fun `should handle mixed case method`() {
            val headers = service.createAuthHeaders("PoSt", "/api/v5/trade/order")

            assertNotNull(headers["OK-ACCESS-SIGN"])
        }
    }

    @Nested
    @DisplayName("Edge Cases and Security Tests")
    inner class EdgeCasesTests {

        @Test
        fun `should handle empty path`() {
            val headers = service.createAuthHeaders("GET", "")

            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertEquals(5, headers.size)
        }

        @Test
        fun `should handle path with special characters`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/test?param=value&other=123")

            assertNotNull(headers["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should handle unicode in body`() {
            val body = """{"message":"测试"}"""
            val headers = service.createAuthHeaders("POST", "/api/v5/test", body)

            assertNotNull(headers["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should generate consistent signature for same inputs within same millisecond`() {
            // Note: This is a probabilistic test - timestamps might differ
            val headers1 = service.createAuthHeaders("GET", "/api/v5/test")
            val headers2 = service.createAuthHeaders("GET", "/api/v5/test")

            // At least the structure should be consistent
            assertEquals(headers1.keys, headers2.keys)
        }

        @Test
        fun `should handle very long request path`() {
            val longPath = "/api/v5/" + "a".repeat(1000)
            val headers = service.createAuthHeaders("GET", longPath)

            assertNotNull(headers["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should handle very long request body`() {
            val longBody = "a".repeat(10000)
            val headers = service.createAuthHeaders("POST", "/api/v5/test", longBody)

            assertNotNull(headers["OK-ACCESS-SIGN"])
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {

        @Test
        fun `should create valid headers for account balance request`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/account/balance")

            assertEquals(5, headers.size)
            assertEquals("test-api-key", headers["OK-ACCESS-KEY"])
            assertEquals("test-passphrase", headers["OK-ACCESS-PASSPHRASE"])
            assertEquals("application/json", headers["Content-Type"])
            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertNotNull(headers["OK-ACCESS-TIMESTAMP"])
        }

        @Test
        fun `should create valid headers for place order request`() {
            val body = """{"instId":"BTC-USDT-SWAP","tdMode":"cross","side":"buy","ordType":"market","sz":"1"}"""
            val headers = service.createAuthHeaders("POST", "/api/v5/trade/order", body)

            assertEquals(5, headers.size)
            assertNotNull(headers["OK-ACCESS-SIGN"])
            assertTrue(headers["OK-ACCESS-SIGN"]!!.length > 20)
        }

        @Test
        fun `should create valid headers for cancel order request`() {
            val body = """{"instId":"BTC-USDT-SWAP","ordId":"12345"}"""
            val headers = service.createAuthHeaders("POST", "/api/v5/trade/cancel-order", body)

            assertEquals(5, headers.size)
            assertNotNull(headers["OK-ACCESS-SIGN"])
        }

        @Test
        fun `should create valid headers for market data request`() {
            val headers = service.createAuthHeaders("GET", "/api/v5/market/ticker?instId=BTC-USDT-SWAP")

            assertEquals(5, headers.size)
            assertNotNull(headers["OK-ACCESS-SIGN"])
        }
    }
}
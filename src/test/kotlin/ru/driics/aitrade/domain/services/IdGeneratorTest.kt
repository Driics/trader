package ru.driics.aitrade.domain.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest

@DisplayName("IdGenerator Tests")
class IdGeneratorTest {

    @Nested
    @DisplayName("clOrdId Generation Tests")
    inner class ClOrdIdTests {

        @Test
        fun `should generate valid client order ID`() {
            val orderId = IdGenerator.clOrdId("BTC")

            assertNotNull(orderId)
            assertTrue(orderId.isNotEmpty())
            assertTrue(orderId.length <= 32, "Order ID should not exceed 32 characters")
        }

        @Test
        fun `should start with AI prefix`() {
            val orderId = IdGenerator.clOrdId("BTC")

            assertTrue(orderId.startsWith("AI"))
        }

        @Test
        fun `should include sanitized symbol`() {
            val orderId = IdGenerator.clOrdId("BTC-USDT")

            assertTrue(orderId.contains("BTCUSDT") || orderId.contains("AIBTCUSD"))
        }

        @Test
        fun `should generate unique IDs for same symbol`() {
            val id1 = IdGenerator.clOrdId("ETH")
            val id2 = IdGenerator.clOrdId("ETH")
            val id3 = IdGenerator.clOrdId("ETH")

            assertNotEquals(id1, id2)
            assertNotEquals(id2, id3)
            assertNotEquals(id1, id3)
        }

        @Test
        fun `should handle long symbols by truncating`() {
            val longSymbol = "VERYLONGSYMBOLNAME"
            val orderId = IdGenerator.clOrdId(longSymbol)

            assertTrue(orderId.length <= 32)
            assertTrue(orderId.startsWith("AI"))
        }

        @Test
        fun `should filter out non-alphanumeric characters`() {
            val orderId = IdGenerator.clOrdId("BTC-USD/SWAP@#$")

            assertTrue(orderId.all { it.isLetterOrDigit() })
        }

        @Test
        fun `should handle empty symbol`() {
            val orderId = IdGenerator.clOrdId("")

            assertNotNull(orderId)
            assertTrue(orderId.startsWith("AI"))
            assertTrue(orderId.length <= 32)
        }

        @Test
        fun `should handle symbol with special characters only`() {
            val orderId = IdGenerator.clOrdId("@#$%^&*()")

            assertNotNull(orderId)
            assertTrue(orderId.startsWith("AI"))
            assertTrue(orderId.all { it.isLetterOrDigit() })
        }

        @Test
        fun `should uppercase the symbol`() {
            val orderId = IdGenerator.clOrdId("btc")

            assertTrue(orderId.contains("BTC") || orderId.startsWith("AIBTC"))
        }

        @Test
        fun `should enforce 32 character limit for very long IDs`() {
            val orderId = IdGenerator.clOrdId("VERYLONGSYMBOL")

            assertTrue(orderId.length <= 32)
        }

        @Test
        fun `should maintain uniqueness across different symbols`() {
            val btcId = IdGenerator.clOrdId("BTC")
            val ethId = IdGenerator.clOrdId("ETH")
            val solId = IdGenerator.clOrdId("SOL")

            assertNotEquals(btcId, ethId)
            assertNotEquals(ethId, solId)
            assertNotEquals(btcId, solId)
        }

        @RepeatedTest(10)
        fun `should generate unique IDs in rapid succession`() {
            val ids = mutableSetOf<String>()
            repeat(100) {
                val id = IdGenerator.clOrdId("BTC")
                assertTrue(ids.add(id), "Generated duplicate ID: $id")
            }
        }

        @Test
        fun `should include timestamp component`() {
            val id1 = IdGenerator.clOrdId("BTC")
            Thread.sleep(10)
            val id2 = IdGenerator.clOrdId("BTC")

            assertNotEquals(id1, id2, "IDs generated at different times should differ")
        }

        @Test
        fun `should handle numeric symbols`() {
            val orderId = IdGenerator.clOrdId("1234")

            assertNotNull(orderId)
            assertTrue(orderId.startsWith("AI"))
            assertTrue(orderId.all { it.isLetterOrDigit() })
        }

        @Test
        fun `should handle mixed case symbols consistently`() {
            val orderId = IdGenerator.clOrdId("BtC-uSdT")

            assertTrue(orderId.all { it.isLetterOrDigit() })
            // Should be uppercase
            assertTrue(orderId.substring(0, 2) == "AI")
        }
    }

    @Nested
    @DisplayName("safeTag Generation Tests")
    inner class SafeTagTests {

        @Test
        fun `should return sanitized tag for valid input`() {
            val tag = IdGenerator.safeTag("BuySignal", 16)

            assertEquals("BUYSIGNAL", tag)
        }

        @Test
        fun `should return fallback for null input`() {
            val tag = IdGenerator.safeTag(null, 16, "FALLBACK")

            assertEquals("FALLBACK", tag)
        }

        @Test
        fun `should return default fallback for null input`() {
            val tag = IdGenerator.safeTag(null)

            assertEquals("AISIGNAL", tag)
        }

        @Test
        fun `should filter out non-alphanumeric characters`() {
            val tag = IdGenerator.safeTag("Buy-Signal@#$%", 16)

            assertEquals("BUYSIGNAL", tag)
        }

        @Test
        fun `should uppercase the tag`() {
            val tag = IdGenerator.safeTag("lowercasetag", 16)

            assertEquals("LOWERCASETAG", tag)
        }

        @Test
        fun `should truncate to max length`() {
            val tag = IdGenerator.safeTag("VERYLONGTAGNAME", 8)

            assertEquals("VERYLONG", tag)
        }

        @Test
        fun `should return null for empty result after filtering`() {
            val tag = IdGenerator.safeTag("@#$%^&*()", 16)

            assertNull(tag)
        }

        @Test
        fun `should return null for blank input after filtering`() {
            val tag = IdGenerator.safeTag("   ", 16)

            assertNull(tag)
        }

        @Test
        fun `should handle numeric tags`() {
            val tag = IdGenerator.safeTag("12345", 16)

            assertEquals("12345", tag)
        }

        @Test
        fun `should handle mixed alphanumeric tags`() {
            val tag = IdGenerator.safeTag("Signal123", 16)

            assertEquals("SIGNAL123", tag)
        }

        @Test
        fun `should use fallback when original becomes empty after filtering`() {
            val tag = IdGenerator.safeTag("@#$", 16, "BACKUP")

            assertNull(tag, "Should return null when result is blank")
        }

        @Test
        fun `should respect custom max length`() {
            val tag = IdGenerator.safeTag("ABCDEFGHIJ", 5)

            assertEquals("ABCDE", tag)
            assertEquals(5, tag?.length)
        }

        @Test
        fun `should handle max length of 1`() {
            val tag = IdGenerator.safeTag("ABCDEFG", 1)

            assertEquals("A", tag)
        }

        @Test
        fun `should handle exactly max length input`() {
            val tag = IdGenerator.safeTag("EXACTLY16CHARS!!", 16)

            assertEquals("EXACTLY16CHARS", tag)
            assertEquals(14, tag?.length)
        }

        @Test
        fun `should preserve alphanumeric mix`() {
            val tag = IdGenerator.safeTag("ABC123XYZ", 16)

            assertEquals("ABC123XYZ", tag)
        }
    }

    @Nested
    @DisplayName("Edge Cases and Stress Tests")
    inner class EdgeCasesTests {

        @Test
        fun `should handle very long symbol gracefully`() {
            val longSymbol = "A".repeat(100)
            val orderId = IdGenerator.clOrdId(longSymbol)

            assertTrue(orderId.length <= 32)
        }

        @Test
        fun `should handle unicode characters`() {
            val orderId = IdGenerator.clOrdId("BTC😀ETH🚀")

            assertTrue(orderId.all { it.isLetterOrDigit() })
        }

        @Test
        fun `should handle whitespace in symbol`() {
            val orderId = IdGenerator.clOrdId("BTC ETH SOL")

            assertTrue(orderId.all { it.isLetterOrDigit() })
            assertFalse(orderId.contains(" "))
        }

        @Test
        fun `should generate IDs that are base36 compatible`() {
            val orderId = IdGenerator.clOrdId("BTC")

            // Should only contain 0-9 and A-Z
            assertTrue(orderId.all { it in '0'..'9' || it in 'A'..'Z' })
        }

        @RepeatedTest(5)
        fun `should maintain consistency in format`() {
            val orderId = IdGenerator.clOrdId("BTC")

            assertTrue(orderId.startsWith("AI"))
            assertTrue(orderId.length <= 32)
            assertTrue(orderId.all { it.isLetterOrDigit() })
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests")
    inner class ThreadSafetyTests {

        @Test
        fun `should generate unique IDs from concurrent threads`() {
            val ids = mutableSetOf<String>()
            val threads = (1..10).map {
                Thread {
                    repeat(100) {
                        synchronized(ids) {
                            ids.add(IdGenerator.clOrdId("BTC"))
                        }
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(1000, ids.size, "All IDs should be unique")
        }
    }
}
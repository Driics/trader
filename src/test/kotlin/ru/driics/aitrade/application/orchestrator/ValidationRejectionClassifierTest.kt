package ru.driics.aitrade.application.orchestrator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S9: free-text schema-rejection reasons must collapse to a small, bounded set of metric codes so
 * the "reason" tag cannot explode metric cardinality.
 */
class ValidationRejectionClassifierTest {

    @Test
    fun `structural reasons map to stable codes`() {
        assertEquals("json_invalid", classifyValidationRejection("Invalid JSON format: Unexpected character"))
        assertEquals("parse_failed", classifyValidationRejection("Failed to parse as AiTradeDecisionMap: x"))
        assertEquals("empty", classifyValidationRejection("Empty response"))
    }

    @Test
    fun `field reasons map to stable codes`() {
        assertEquals("bean_validation", classifyValidationRejection("All signals rejected: BTC: Bean validation failed: leverage"))
        assertEquals("coin_mismatch", classifyValidationRejection("Coin mismatch: key=BTC, coin=ETH"))
        assertEquals("confidence_range", classifyValidationRejection("Confidence out of range [0, 1]: 1.4"))
        assertEquals("leverage_range", classifyValidationRejection("Leverage out of range [1, 125]: 200"))
        assertEquals("price_range", classifyValidationRejection("Stop loss must be positive: -1"))
    }

    @Test
    fun `unknown reasons fall through to other`() {
        assertEquals("other", classifyValidationRejection("something entirely unexpected"))
    }

    @Test
    fun `the code set stays bounded across many distinct reasons`() {
        val reasons = listOf(
            "Invalid JSON format: a", "Invalid JSON format: b",
            "Confidence out of range: 1.1", "Confidence out of range: 2.0",
            "Leverage out of range: 200", "Leverage out of range: 300",
            "weird one", "another weird one",
        )
        val codes = reasons.map { classifyValidationRejection(it) }.toSet()
        // 8 distinct free-text reasons collapse to at most a handful of codes.
        assertTrue(codes.size <= 4, "expected bounded cardinality, got $codes")
    }
}

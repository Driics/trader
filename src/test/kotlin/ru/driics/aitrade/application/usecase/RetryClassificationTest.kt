package ru.driics.aitrade.application.usecase

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S7: transient-error classification. The key regression guard is that HTTP codes match on word
 * boundaries, so "500" is not found inside "5000ms" or a price like "50000".
 */
class RetryClassificationTest {

    @Test
    fun `http 5xx and 429 codes are retryable`() {
        assertTrue(RetryClassification.isRetryable("Server returned HTTP 500"))
        assertTrue(RetryClassification.isRetryable("502 Bad Gateway"))
        assertTrue(RetryClassification.isRetryable("upstream 503"))
        assertTrue(RetryClassification.isRetryable("504 gateway timeout"))
        assertTrue(RetryClassification.isRetryable("got 429 too many requests"))
    }

    @Test
    fun `transient phrases are retryable`() {
        assertTrue(RetryClassification.isRetryable("Request timeout after 60s"))
        assertTrue(RetryClassification.isRetryable("rate limit exceeded"))
        assertTrue(RetryClassification.isRetryable("connection reset by peer"))
        assertTrue(RetryClassification.isRetryable("model temporarily unavailable"))
    }

    @Test
    fun `a status code embedded in a larger number is NOT retryable`() {
        assertFalse(RetryClassification.isRetryable("request took 5000ms"), "5000 must not match 500")
        assertFalse(RetryClassification.isRetryable("entry price 50000 was rejected"), "50000 must not match 500")
        assertFalse(RetryClassification.isRetryable("riskUsd 5024 invalid"))
    }

    @Test
    fun `null and clean business errors are not retryable`() {
        assertFalse(RetryClassification.isRetryable(null))
        assertFalse(RetryClassification.isRetryable("invalid api key"))
        assertFalse(RetryClassification.isRetryable("content policy violation"))
    }
}

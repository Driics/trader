package ru.driics.aitrade.domain.services

import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure domain service for API key rotation.
 * Implements round-robin distribution for uniform key usage.
 * Thread-safe and stateless (except for rotation counter).
 */
class ApiKeyRotationPolicy(
    private val keys: List<String>
) {
    init {
        require(keys.isNotEmpty()) { "At least one API key is required" }
        require(keys.all { it.isNotBlank() }) { "All API keys must be non-blank" }
    }

    private val rotationIndex = AtomicInteger(0)

    /**
     * Returns the current key without advancing the index.
     */
    val currentKey: String
        get() = keys[rotationIndex.get() % keys.size]

    /**
     * Rotates to the next key and returns it.
     * Uses round-robin to ensure uniform distribution.
     */
    fun rotateToNextKey(): String {
        val nextIndex = rotationIndex.incrementAndGet() % keys.size
        return keys[nextIndex]
    }

    /**
     * Returns all available keys (for monitoring/debugging).
     */
    val allKeys: List<String>
        get() = keys.toList()

    /**
     * Returns the total number of available keys.
     */
    val keyCount: Int
        get() = keys.size

    /**
     * Returns the current rotation index (for monitoring).
     */
    val currentIndex: Int
        get() = rotationIndex.get() % keys.size

    /**
     * Resets rotation to the first key.
     */
    fun reset() = rotationIndex.set(0)
}
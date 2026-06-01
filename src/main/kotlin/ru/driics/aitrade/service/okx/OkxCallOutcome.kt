package ru.driics.aitrade.service.okx

/**
 * Typed result of an OKX trading call (S4).
 *
 * Replaces the previous `T?` return, where timeout, HTTP 4xx, HTTP 5xx and generic exceptions all
 * collapsed to `null` — making a network timeout on order placement indistinguishable from a clean
 * exchange rejection. That distinction is safety-critical: after a [TimeoutUnknown] the order MAY
 * have been placed, so callers must NOT treat it like a definitive "did not happen".
 */
sealed interface OkxCallOutcome<out T> {

    /** The exchange returned a successful, parsed response. */
    data class Success<T>(val value: T) : OkxCallOutcome<T>

    /**
     * The exchange definitively rejected the request (HTTP 4xx, or an API/order code != "0").
     * The operation did NOT take effect — safe to treat as a clean "did not happen".
     */
    data class RejectedByExchange(val code: String, val message: String) : OkxCallOutcome<Nothing>

    /**
     * The request timed out before a definitive response. The operation's effect is UNKNOWN — it
     * may or may not have been applied. Callers must not assume it failed.
     */
    data class TimeoutUnknown(val message: String) : OkxCallOutcome<Nothing>

    /** A transport-level failure (HTTP 5xx, connection error, or unexpected exception). */
    data class TransportError(val message: String, val cause: Throwable? = null) : OkxCallOutcome<Nothing>
}

package ru.driics.aitrade.domain.types

import java.math.BigDecimal
import kotlin.time.Duration

sealed interface TradeResult<out T> {
    data class Success<T>(val value: T) : TradeResult<T>

    sealed interface Failure : TradeResult<Nothing> {
        val message: String
        val cause: Throwable?

        data class InsufficientBalance(
            val required: BigDecimal,
            val available: BigDecimal,
            override val message: String = "Insufficient balance: required $required, available $available",
            override val cause: Throwable? = null
        ) : Failure

        data class InvalidPrice(
            val symbol: Symbol,
            val price: BigDecimal,
            val reason: String,
            override val message: String = "Invalid price for $symbol: $price ($reason)",
            override val cause: Throwable? = null
        ) : Failure

        data class ApiError(
            val code: String,
            override val message: String,
            override val cause: Throwable? = null
        ) : Failure

        data class NetworkError(
            override val message: String,
            override val cause: Throwable
        ) : Failure

        data class RateLimitExceeded(
            val retryAfter: Duration,
            override val message: String = "Rate limit exceeded, retry after $retryAfter",
            override val cause: Throwable? = null
        ) : Failure

        data class ValidationError(
            val field: String,
            val constraint: String,
            override val message: String = "Validation failed for $field: $constraint",
            override val cause: Throwable? = null
        ) : Failure
    }
}

inline fun <T> TradeResult<T>.onSuccess(block: (T) -> Unit): TradeResult<T> {
    if (this is TradeResult.Success) block(value)
    return this
}

inline fun <T> TradeResult<T>.onFailure(block: (TradeResult.Failure) -> Unit): TradeResult<T> {
    if (this is TradeResult.Failure) block(this)
    return this
}

fun <T> TradeResult<T>.getOrNull(): T? = when (this) {
    is TradeResult.Success -> value
    is TradeResult.Failure -> null
}

fun <T> TradeResult<T>.getOrThrow(): T = when (this) {
    is TradeResult.Success -> value
    is TradeResult.Failure -> throw RuntimeException(message, cause)
}

inline fun <T, R> TradeResult<T>.map(transform: (T) -> R): TradeResult<R> = when (this) {
    is TradeResult.Success -> TradeResult.Success(transform(value))
    is TradeResult.Failure -> this
}

inline fun <T, R> TradeResult<T>.flatMap(transform: (T) -> TradeResult<R>): TradeResult<R> = when (this) {
    is TradeResult.Success -> transform(value)
    is TradeResult.Failure -> this
}
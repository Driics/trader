package ru.driics.aitrade.common

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

suspend inline fun <T> withTimeoutAndLog(
    timeoutMs: Long,
    logger: KLogger?,
    operation: String,
    crossinline block: suspend () -> T
): T = try {
    withTimeout(timeoutMs) { block() }
} catch (e: TimeoutCancellationException) {
    logger?.warn { "$operation timed out after ${timeoutMs}ms" }
    throw e
}
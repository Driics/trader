package ru.driics.aitrade.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import ru.driics.aitrade.application.ai.AiBudgetLimiter
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiAnalysisResponse
import ru.driics.aitrade.domain.ports.AiAnalysisPort
import kotlin.time.Duration.Companion.milliseconds

class AnalyzePromptUseCase(
    private val ai: AiAnalysisPort,
    private val tradingProperties: TradingProperties,
    private val budgetLimiter: AiBudgetLimiter,
    private val meterRegistry: MeterRegistry
) {
    private companion object {
        val log = KotlinLogging.logger {}
        const val METRIC_PREFIX = "ai.request"
    }

    // Pre-define metrics to avoid allocation during execution
    private val successCounter = meterRegistry.counter("$METRIC_PREFIX.success")
    private val errorCounter = meterRegistry.counter("$METRIC_PREFIX.error")
    private val timeoutCounter = meterRegistry.counter("$METRIC_PREFIX.timeout")
    private val retryCounter = meterRegistry.counter("$METRIC_PREFIX.retry")
    private val budgetExceededCounter = meterRegistry.counter("$METRIC_PREFIX.budget_exceeded")
    private val latencyTimer = meterRegistry.timer("ai.latency")

    suspend fun execute(prompt: String): AiAnalysisResponse {
        // 1. Budget Check
        if (!budgetLimiter.tryConsume()) {
            budgetExceededCounter.increment()
            log.warn { "AI budget exceeded (${tradingProperties.aiBudgetPerMinute} req/min)" }
            return failureResponse(
                provider = "budget-limiter",
                message = "Budget limit exceeded: ${tradingProperties.aiBudgetPerMinute} req/min"
            )
        }

        val maxRetries = tradingProperties.aiMaxRetries
        val timeoutMs = tradingProperties.aiTimeoutMs

        // 2. Execution with Retry Policy
        var lastException: Throwable? = null

        repeat(maxRetries + 1) { attempt ->
            val isLastAttempt = attempt == maxRetries

            try {
                // Measure latency only for the actual AI call, not the retry logic
                val response = latencyTimer.recordSuspend {
                    withTimeout(timeoutMs.milliseconds) {
                        ai.analyze(prompt)
                    }
                }

                if (response.isSuccess) {
                    successCounter.increment()
                    BusinessEventLogger.aiDecision("ALL", "analysis_complete", null, null, null)
                    return response
                }

                // Handle soft failure (API returned 200 but content implies error)
                if (!isLastAttempt && response.errorMessage.isRetryable()) {
                    handleRetry(attempt, maxRetries, response.errorMessage)
                    return@repeat // continue loop
                } else if (isLastAttempt) {
                    errorCounter.increment()
                    return response
                }

            } catch (e: TimeoutCancellationException) {
                lastException = e
                timeoutCounter.increment()
                if (!isLastAttempt) {
                    handleRetry(attempt, maxRetries, "Timeout ${timeoutMs}ms")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e // Don't catch coroutine cancellation

                lastException = e
                if (!isLastAttempt && e.message.isRetryable()) {
                    handleRetry(attempt, maxRetries, e.message)
                } else {
                    // Non-retryable error or last attempt
                    log.error(e) { "AI call failed permanently after ${attempt + 1} attempts" }
                    errorCounter.increment()
                    return failureResponse(
                        provider = "error",
                        message = "AI request failed: ${e.message}"
                    )
                }
            }
        }

        // 3. Fallback for exhausted retries
        errorCounter.increment()
        return failureResponse(
            provider = "exhausted",
            message = "Failed after ${maxRetries + 1} attempts. Last error: ${lastException?.message}"
        )
    }

    private suspend fun handleRetry(attempt: Int, maxRetries: Int, reason: String?) {
        retryCounter.increment()
        log.warn { "AI call issue (attempt ${attempt + 1}/${maxRetries + 1}): $reason. Retrying..." }
        // Optional: Add exponential backoff here if desired
        delay(500L * (attempt + 1))
    }

    /**
     * Extension to determine if an error string suggests a transient issue.
     */
    private fun String?.isRetryable(): Boolean {
        if (this == null) return false
        val msg = this.lowercase()
        return msg.contains("timeout") ||
                msg.contains("rate limit") ||
                msg.contains("503") ||
                msg.contains("502") ||
                msg.contains("500") ||
                msg.contains("connection") ||
                msg.contains("reset")
    }

    private fun failureResponse(provider: String, message: String) = AiAnalysisResponse(
        provider = provider,
        model = "none",
        response = "",
        executionTimeMs = 0,
        isSuccess = false,
        errorMessage = message
    )

    // Helper for Micrometer suspend recording
    private suspend fun <T> Timer.recordSuspend(block: suspend () -> T): T {
        val sample = Timer.start(meterRegistry)
        return try {
            block()
        } finally {
            sample.stop(this)
        }
    }
}
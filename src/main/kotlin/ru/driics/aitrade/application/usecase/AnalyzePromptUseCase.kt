package ru.driics.aitrade.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.TimeoutCancellationException
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
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val timeoutCounter: Counter = meterRegistry.counter("ai.request.timeout")
    private val retryCounter: Counter = meterRegistry.counter("ai.request.retry")
    private val budgetExceededCounter: Counter = meterRegistry.counter("ai.request.budget_exceeded")
    private val successCounter: Counter = meterRegistry.counter("ai.request.success")
    private val errorCounter: Counter = meterRegistry.counter("ai.request.error")

    private val latencyTimer: Timer = meterRegistry.timer("ai.latency")

    suspend fun execute(prompt: String): AiAnalysisResponse {
        // Check budget before making request
        if (!budgetLimiter.tryConsume()) {
            budgetExceededCounter.increment()
            log.warn { "AI request rejected: budget limit exceeded (${tradingProperties.aiBudgetPerMinute} req/min)" }
            return AiAnalysisResponse(
                provider = "budget-limiter",
                model = "none",
                response = "",
                executionTimeMs = 0,
                isSuccess = false,
                errorMessage = "AI budget limit exceeded: ${tradingProperties.aiBudgetPerMinute} requests/min"
            )
        }

        var lastError: Throwable? = null
        val maxRetries = tradingProperties.aiMaxRetries
        val timeoutMs = tradingProperties.aiTimeoutMs

        // Retry loop for transient errors
        for (attempt in 0..maxRetries) {
            try {
                val sample = Timer.start(meterRegistry)
                val response = withTimeout(timeoutMs.milliseconds) {
                    ai.analyze(prompt)
                }
                sample.stop(latencyTimer)

                if (response.isSuccess) {
                    successCounter.increment()
                    BusinessEventLogger.aiDecision(
                        symbol = "ALL",
                        signal = "analysis_complete",
                        confidence = null,
                        leverage = null,
                        riskUsd = null
                    )
                    return response
                } else {
                    // Non-success response - might be retryable
                    if (attempt < maxRetries && isRetryableError(response.errorMessage)) {
                        retryCounter.increment()
                        log.warn { "AI call failed (attempt ${attempt + 1}/${maxRetries + 1}): ${response.errorMessage}, retrying..." }
                        continue
                    } else {
                        errorCounter.increment()
                        return response
                    }
                }
            } catch (e: TimeoutCancellationException) {
                timeoutCounter.increment()
                lastError = e
                if (attempt < maxRetries) {
                    retryCounter.increment()
                    log.warn { "AI call timed out after ${timeoutMs}ms (attempt ${attempt + 1}/${maxRetries + 1}), retrying..." }
                    continue
                } else {
                    log.error(e) { "AI call timed out after ${maxRetries + 1} attempts" }
                    errorCounter.increment()
                    return AiAnalysisResponse(
                        provider = "timeout",
                        model = "none",
                        response = "",
                        executionTimeMs = timeoutMs * (maxRetries + 1),
                        isSuccess = false,
                        errorMessage = "AI request timed out after ${timeoutMs}ms (${maxRetries + 1} attempts)"
                    )
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries && isRetryableError(e.message)) {
                    retryCounter.increment()
                    log.warn(e) { "AI call failed (attempt ${attempt + 1}/${maxRetries + 1}), retrying..." }
                    continue
                } else {
                    log.error(e) { "AI call failed after ${attempt + 1} attempts" }
                    errorCounter.increment()
                    return AiAnalysisResponse(
                        provider = "error",
                        model = "none",
                        response = "",
                        executionTimeMs = 0,
                        isSuccess = false,
                        errorMessage = "AI request failed: ${e.message}"
                    )
                }
            }
        }

        // Should not reach here, but handle it
        errorCounter.increment()
        return AiAnalysisResponse(
            provider = "unknown",
            model = "none",
            response = "",
            executionTimeMs = 0,
            isSuccess = false,
            errorMessage = "AI request failed after ${maxRetries + 1} attempts: ${lastError?.message}"
        )
    }

    private fun isRetryableError(errorMessage: String?): Boolean {
        if (errorMessage == null) return false
        val msg = errorMessage.lowercase()
        // Retry on network errors, rate limits, temporary service errors
        return msg.contains("timeout", ignoreCase = true) ||
                msg.contains("rate limit", ignoreCase = true) ||
                msg.contains("503", ignoreCase = true) ||
                msg.contains("502", ignoreCase = true) ||
                msg.contains("500", ignoreCase = true) ||
                msg.contains("connection", ignoreCase = true)
    }
}
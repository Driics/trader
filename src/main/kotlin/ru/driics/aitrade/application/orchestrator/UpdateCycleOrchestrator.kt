package ru.driics.aitrade.application.orchestrator

import ru.driics.aitrade.application.ai.AiSchemaValidator
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.ai.SignalNormalizer
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.common.timedSuspend
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AIAction
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiTradeDecisionMap
import ru.driics.aitrade.domain.model.AiTradeEnvelope
import ru.driics.aitrade.domain.model.AiTradeExecutionResult
import ru.driics.aitrade.domain.model.Position
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.types.asSymbol
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import ru.driics.aitrade.common.timedSuspend
import ru.driics.aitrade.common.traced
import ru.driics.aitrade.common.logging.CorrelationId
import io.opentelemetry.api.trace.Tracer
import java.security.MessageDigest
import java.time.Clock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class UpdateCycleOrchestrator(
    private val build: BuildPromptUseCase,
    private val analyze: AnalyzePromptUseCase,
    private val execute: ExecuteAiDecisionsUseCase,
    private val market: MarketDataPort,
    private val meterRegistry: MeterRegistry,
    private val autoExecute: Boolean,
    private val symbols: List<String>,
    private val clock: Clock,
    private val schemaValidator: AiSchemaValidator,
    private val tradingProperties: TradingProperties,
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val tracer: Tracer
) {
    companion object {
        private val log = logger<UpdateCycleOrchestrator>()
        private const val RESPONSE_LOG_LIMIT = 500
    }

    private val sessionStartTime = AtomicLong(clock.instant().toEpochMilli())
    private val invocationCount = AtomicLong(0L)
    private val lastUpdateTime = AtomicLong(0L)
    private val lastPromptHash = AtomicReference<String?>(null)
    
    // Metrics for AI response validation rejections
    private fun getValidationRejectionCounter(reason: String): Counter {
        // Normalize reason for metric tag (remove special chars, limit length)
        val normalizedReason = reason
            .take(50)
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .lowercase()
        return meterRegistry.counter("ai.response.validation.rejected", "reason", normalizedReason)
    }

    suspend fun runOnce(): UpdateCycleResult {
        val invocation = invocationCount.incrementAndGet()
        val correlationId = CorrelationId.generate()

        log.info { "╔══════════════════════════════════════════════════════" }
        log.info { "║ Update Cycle #$invocation" }
        log.info { "╚══════════════════════════════════════════════════════" }

        val (result, took) = tracer.traced("update.cycle", {
            attr("cycleNumber", invocation)
            attr("autoExecute", autoExecute)
            attr("correlationId", correlationId)
        }) { span ->
            meterRegistry.timedSuspend(
                "update.cycle",
                "autoExecute", autoExecute.toString()
            ) {
                doRunOnce(invocation, correlationId)
            }
        }

        lastUpdateTime.set(clock.instant().toEpochMilli())

        log.info { "═══ Update cycle #$invocation completed in ${took}ms ═══\n" }

        return result.copy(executionTimeMs = took)
    }

    private suspend fun doRunOnce(invocation: Long, correlationId: String): UpdateCycleResult {
        // Stage 1: Build prompt
        val (prompt, buildPromptTime) = tracer.traced("update.cycle.build_prompt", {
            attr("correlationId", correlationId)
        }) { span ->
            meterRegistry.timedSuspend("update.cycle.build_prompt") {
                buildPrompt(invocation)
            }
        } ?: return errorResult("Failed to build prompt", 0)

        // Check if prompt changed
        if (isPromptUnchanged(prompt)) {
            log.info { "Prompt unchanged from previous cycle, skipping AI analysis" }
            return UpdateCycleResult(
                success = true,
                message = "Skipped (prompt unchanged)",
                executionTimeMs = buildPromptTime,
                promptSize = prompt.length,
                positionsPlaced = 0
            )
        }

        // Stage 2: AI analysis
        val (aiResult, aiCallTime) = tracer.traced("update.cycle.ai_call", {
            attr("correlationId", correlationId)
            attr("promptSize", prompt.length.toLong())
        }) { span ->
            meterRegistry.timedSuspend("update.cycle.ai_call") {
                analyzePrompt(prompt)
            }
        } ?: return errorResult("Failed to analyze prompt", prompt.length)
        
        if (!aiResult.isSuccess) {
            meterRegistry.counter("update.cycle.ai_call.error").increment()
            log.error { "AI analysis failed: ${aiResult.errorMessage}" }
            return errorResult("AI analysis failed - ${aiResult.errorMessage}", prompt.length)
        }

        log.info { "AI analysis completed successfully" }

        // Stage 3: Validate and parse AI response
        val (rawDecisions, parseTime) = tracer.traced("update.cycle.parse", {
            attr("correlationId", correlationId)
            attr("responseSize", aiResult.response.length.toLong())
        }) { span ->
            meterRegistry.timedSuspend("update.cycle.parse") {
                validateAndParseResponse(aiResult.response, prompt.length)
            }
        } ?: run {
            meterRegistry.counter("update.cycle.parse.error").increment()
            return errorResult("AI response validation failed", prompt.length)
        }

        // Stage 4: Normalize and calibrate signals (guard/calibration happens in ExecuteAiDecisionsUseCase)
        val (decisions, guardTime) = tracer.traced("update.cycle.guard", {
            attr("correlationId", correlationId)
            attr("rawDecisionsCount", rawDecisions.size.toLong())
        }) { span ->
            meterRegistry.timedSuspend("update.cycle.guard") {
                normalizeAndCalibrateSignals(rawDecisions, prompt.length)
            }
        } ?: run {
            meterRegistry.counter("update.cycle.guard.error").increment()
            return errorResult("All signals rejected after normalization/calibration", prompt.length)
        }

        logAiDecisionsSummary(decisions)

        // Stage 5: Execute decisions (if enabled)
        val (executionResults, executeTime) = if (autoExecute) {
            tracer.traced("update.cycle.execute", {
                attr("correlationId", correlationId)
                attr("decisionsCount", decisions.size.toLong())
            }) { span ->
                meterRegistry.timedSuspend("update.cycle.execute") {
                    executeDecisions(decisions, invocation)
                }
            } ?: run {
                meterRegistry.counter("update.cycle.execute.error").increment()
                return errorResult(
                    "AI succeeded but execution failed",
                    prompt.length
                )
            }
        } else {
            log.info { "Auto-execution disabled, skipping trade placement" }
            null to 0L
        }

        val positionsPlaced = executionResults?.count { it.action == AIAction.PLACED } ?: 0

        // Log structured update cycle event
        BusinessEventLogger.updateCycle(
            cycleNumber = invocation,
            durationMs = 0, // Will be calculated by caller
            symbolsProcessed = decisions.size,
            positionsPlaced = positionsPlaced,
            success = true
        )

        return UpdateCycleResult(
            success = true,
            message = "Success",
            executionTimeMs = 0,
            promptSize = prompt.length,
            positionsPlaced = positionsPlaced
        )
    }

    private suspend fun buildPrompt(invocation: Long): String? = try {
        build.execute(symbols, sessionStartTime.get(), invocation)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        meterRegistry.counter("update.cycle.build_prompt.error").increment()
        log.error(e) { "Failed to build prompt" }
        null
    }

    private fun isPromptUnchanged(prompt: String): Boolean {
        val currentHash = hashPrompt(prompt)
        val previousHash = lastPromptHash.get()
        if (currentHash == previousHash) return true
        lastPromptHash.set(currentHash)
        return false
    }

    private suspend fun analyzePrompt(prompt: String) = try {
        analyze.execute(prompt)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        meterRegistry.counter("update.cycle.ai_call.error").increment()
        log.error(e) { "Failed to analyze prompt" }
        null
    }

    private fun validateAndParseResponse(
        response: String,
        promptSize: Int
    ): AiTradeDecisionMap? {
        val validationResult = schemaValidator.validateAndParse(response)
        return when (validationResult) {
            is AiSchemaValidator.ValidationResult.Valid -> validationResult.decisions
            is AiSchemaValidator.ValidationResult.Rejected -> {
                // Record validation rejection metric
                getValidationRejectionCounter(validationResult.reason).increment()
                
                log.error { "AI response validation failed: ${validationResult.reason}" }
                BusinessEventLogger.error(
                    event = "ai_response_validation_failed",
                    error = IllegalArgumentException(validationResult.reason),
                    "response" to response.take(RESPONSE_LOG_LIMIT)
                )
                null
            }
        }
    }

    private fun normalizeAndCalibrateSignals(
        rawDecisions: AiTradeDecisionMap,
        promptSize: Int
    ): AiTradeDecisionMap? {
        val normalizer = SignalNormalizer()
        val normalizedDecisions = mutableMapOf<String, AiTradeEnvelope>()
        var normalizedCount = 0
        var rejectedCount = 0

        for ((symbol, envelope) in rawDecisions) {
            val normalized = normalizer.normalize(envelope.args)
                ?: run {
                    rejectedCount++
                    log.warn { "Signal normalization failed for $symbol" }
                    continue
                }

            val calibration = confidenceCalibrator.shouldAccept(normalized.toAiTradeSignalArgs())
            when (calibration) {
                is ConfidenceCalibrator.CalibrationResult.Accepted -> {
                    normalizedDecisions[symbol] = AiTradeEnvelope(
                        args = normalized.toAiTradeSignalArgs()
                    )
                    normalizedCount++
                }
                is ConfidenceCalibrator.CalibrationResult.Rejected -> {
                    rejectedCount++
                    log.info { "Signal rejected for $symbol: ${calibration.reason}" }
                }
            }
        }

        if (normalizedDecisions.isEmpty()) {
            log.warn { "All signals rejected after normalization/calibration ($rejectedCount total)" }
            return null
        }

        log.info { "Signal processing: $normalizedCount accepted, $rejectedCount rejected" }
        return normalizedDecisions
    }

    private suspend fun executeDecisions(
        decisions: AiTradeDecisionMap,
        invocation: Long
    ): List<AiTradeExecutionResult>? = try {
        val results = execute.execute(decisions)
        log.info { "Trade execution completed: ${results.size} decisions processed" }
        results
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        meterRegistry.counter("update.cycle.execute.error").increment()
        BusinessEventLogger.error(
            event = "update_cycle_execution_failed",
            error = e,
            "cycleNumber" to invocation,
            "decisionsCount" to decisions.size
        )
        null
    }

    private fun errorResult(message: String, promptSize: Int): UpdateCycleResult =
        UpdateCycleResult(
            success = false,
            message = "Error: $message",
            executionTimeMs = 0,
            promptSize = promptSize
        )

    suspend fun getAccountInfo(): AccountInfo = market.loadMarketState(symbols.map { it.asSymbol() }).account

    suspend fun getPositions(): List<Position> = market.loadMarketState(symbols.map { it.asSymbol() }).positions

    fun getSessionStartTime(): Long = sessionStartTime.get()

    fun getInvocationCount(): Long = invocationCount.get()

    fun getLastUpdateTime(): Long? = lastUpdateTime.get().takeIf { it > 0 }

    private fun logAiDecisionsSummary(decisions: AiTradeDecisionMap) {
        try {
            log.info { "═══ AI Decisions Summary (${decisions.size} symbols) ═══" }
            decisions.forEach { (symbol, envelope) ->
                val details = formatDecisionDetails(symbol, envelope)
                log.info { "  • $details" }
            }
        } catch (e: Exception) {
            log.warn(e) { "Could not log AI decisions summary" }
        }
    }

    private fun formatDecisionDetails(symbol: String, envelope: AiTradeEnvelope): String {
        val args = envelope.args
        val signal = args.signal.name
        
        return buildString {
            append("$symbol: $signal")
            if (signal != "HOLD") {
                args.confidence?.let { 
                    append(" | Confidence: ${String.format(Locale.ROOT, "%.2f", it.toDouble())}") 
                }
                args.leverage?.let { append(" | Leverage: $it") }
                args.quantity?.let { 
                    append(" | Qty: ${String.format(Locale.ROOT, "%.4f", it.toDouble())}") 
                }
                args.riskUsd?.let { 
                    append(" | Risk: $${String.format(Locale.ROOT, "%.2f", it.toDouble())}") 
                }
            }
        }
    }

    private fun hashPrompt(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(prompt.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

data class UpdateCycleResult(
    val success: Boolean,
    val message: String,
    val executionTimeMs: Long,
    val promptSize: Int,
    val positionsPlaced: Int = 0
)
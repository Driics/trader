package ru.driics.aitrade.application.orchestrator

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CancellationException
import org.slf4j.MDC
import ru.driics.aitrade.application.ai.AiSchemaValidator
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.ai.SignalNormalizer
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.common.logging.CorrelationId
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.common.measureSuspend
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.services.TradingMetricsService
import ru.driics.aitrade.domain.types.asSymbol
import java.security.MessageDigest
import java.time.Clock
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class UpdateCycleOrchestrator(
    private val build: BuildPromptUseCase,
    private val analyze: AnalyzePromptUseCase,
    private val execute: ExecuteAiDecisionsUseCase,
    private val market: MarketDataPort,
    private val meterRegistry: MeterRegistry,
    private val tradingMetricsService: TradingMetricsService,
    private val autoExecute: Boolean,
    private val symbols: List<String>,
    private val clock: Clock,
    private val schemaValidator: AiSchemaValidator,
    private val tradingProperties: TradingProperties,
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val tracer: Tracer
) {
    private companion object {
        val log = logger<UpdateCycleOrchestrator>()
        const val RESPONSE_LOG_LIMIT = 500
        const val METRIC_CYCLE = "update.cycle"
        const val METRIC_VALIDATION_REJECTED = "ai.response.validation.rejected"
        const val MDC_CORRELATION_ID = "correlationId"
    }

    // State
    private val sessionStartTime = AtomicLong(clock.instant().toEpochMilli())
    private val invocationCount = AtomicLong(0L)
    private val lastUpdateTime = AtomicLong(0L)
    private val lastPromptHash = AtomicReference<String?>(null)

    // Components
    private val normalizer = SignalNormalizer()

    suspend fun runOnce(): UpdateCycleResult {
        val invocation = invocationCount.incrementAndGet()
        val correlationId = CorrelationId.generate()
        val start = System.currentTimeMillis()

        // 1. Setup Root Span & MDC
        return traceAndMeasure("run_once", correlationId, mapOf(
            "cycleNumber" to invocation,
            "autoExecute" to autoExecute
        )) { span ->

            // Ensure MDC is set for the scope of execution
            withMdc(correlationId) {
                logHeader(invocation)

                try {
                    // 2. Execute Pipeline
                    val result = executePipeline(invocation, correlationId)

                    // Add dynamic result attributes to the root span
                    span.setAttribute("success", result.success)
                    span.setAttribute("positionsPlaced", result.positionsPlaced.toLong())
                    span.setAttribute("message", result.message)

                    val duration = System.currentTimeMillis() - start
                    lastUpdateTime.set(clock.instant().toEpochMilli())

                    logFooter(invocation, duration)

                    if (!result.success) {
                        span.setStatus(StatusCode.ERROR, result.message)
                    }

                    result.copy(executionTimeMs = duration)

                } catch (e: Exception) {
                    log.error(e) { "Critical failure in update cycle" }
                    span.recordException(e)
                    span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
                    throw e
                }
            }
        }
    }

    /**
     * The main pipeline logic: Build -> Analyze -> Parse -> Guard -> Execute
     */
    private suspend fun executePipeline(invocation: Long, cid: String): UpdateCycleResult {
        // 1. Build Prompt
        val prompt = stageBuildPrompt(invocation, cid)
            ?: return errorResult("Failed to build prompt")

        // 2. Check Diff
        if (isPromptUnchanged(prompt)) {
            log.info { "Prompt unchanged, skipping analysis." }
            Span.current().setAttribute("skipped", true)
            return UpdateCycleResult(true, "Skipped (unchanged)", 0, prompt.length)
        }

        // 3. AI Analysis
        val aiResult = stageAiAnalysis(prompt, cid)
        if (aiResult == null || !aiResult.isSuccess) {
            return errorResult("AI analysis failed: ${aiResult?.errorMessage}", prompt.length)
        }

        // 4. Parse & Validate
        val rawDecisions = stageParseResponse(aiResult.response, prompt.length, cid)
            ?: return errorResult("AI response validation failed", prompt.length)

        // 5. Guard & Normalize
        val finalDecisions = stageGuardAndNormalize(rawDecisions, prompt.length, cid)
            ?: return errorResult("All signals rejected by guardrails", prompt.length)

        logAiDecisionsSummary(finalDecisions)

        // 6. Execute (Wrapper for OKX calls)
        val positionsPlaced = if (autoExecute) {
            stageExecute(finalDecisions, invocation, cid)
                ?: return errorResult("Execution failed after successful AI", prompt.length)
        } else {
            log.info { "Auto-execution disabled." }
            Span.current().setAttribute("execution_skipped", "manual_mode")
            0
        }

        // 7. Success Event
        BusinessEventLogger.updateCycle(invocation, 0, finalDecisions.size, positionsPlaced, true)

        return UpdateCycleResult(true, "Success", 0, prompt.length, positionsPlaced)
    }

    // =========================================================================
    // Pipeline Stages (Wrapped with Tracing & Metrics)
    // =========================================================================

    private suspend fun stageBuildPrompt(invocation: Long, cid: String): String? {
        return traceAndMeasure("build_prompt", cid) {
            try {
                build.execute(symbols, sessionStartTime.get(), invocation)
            } catch (e: Exception) {
                handleStageError("build_prompt", e, it)
                null
            }
        }
    }

    private suspend fun stageAiAnalysis(prompt: String, cid: String): AiAnalysisResponse? {
        return traceAndMeasure("ai_call", cid, mapOf("promptSize" to prompt.length)) {
            try {
                analyze.execute(prompt)
            } catch (e: Exception) {
                handleStageError("ai_call", e, it)
                null
            }
        }
    }

    private suspend fun stageParseResponse(response: String, promptSize: Int, cid: String): AiTradeDecisionMap? {
        return traceAndMeasure("parse_response", cid, mapOf("responseSize" to response.length)) { span ->
            when (val result = schemaValidator.validateAndParse(response)) {
                is AiSchemaValidator.ValidationResult.Valid -> result.decisions
                is AiSchemaValidator.ValidationResult.Rejected -> {
                    recordValidationRejection(result.reason)
                    log.error { "AI schema rejected: ${result.reason}" }
                    span.setStatus(StatusCode.ERROR, "Schema validation failed")
                    span.setAttribute("rejection_reason", result.reason)
                    BusinessEventLogger.error("ai_schema_rejected", IllegalArgumentException(result.reason), "response" to response.take(RESPONSE_LOG_LIMIT))
                    null
                }
            }
        }
    }

    private suspend fun stageGuardAndNormalize(raw: AiTradeDecisionMap, promptSize: Int, cid: String): AiTradeDecisionMap? {
        return traceAndMeasure("guard_normalize", cid, mapOf("rawCount" to raw.size)) { span ->
            val normalized = mutableMapOf<String, AiTradeEnvelope>()
            var rejected = 0

            for ((symbol, envelope) in raw) {
                // Normalize
                val normArgs = normalizer.normalize(envelope.args)?.toAiTradeSignalArgs()
                if (normArgs == null) {
                    rejected++; continue
                }

                // Calibrate/Filter
                when (val cal = confidenceCalibrator.shouldAccept(normArgs)) {
                    is ConfidenceCalibrator.CalibrationResult.Accepted -> {
                        normalized[symbol] = AiTradeEnvelope(normArgs)
                        // Record accepted signal metric
                        tradingMetricsService.recordAiSignal(
                            symbol = symbol,
                            signal = normArgs.signal.toString(),
                            confidence = normArgs.confidence?.toDouble() ?: 0.0
                        )
                    }
                    is ConfidenceCalibrator.CalibrationResult.Rejected -> {
                        log.debug { "Signal rejected ($symbol): ${cal.reason}" }
                        rejected++
                    }
                }
            }

            span.setAttribute("rejected_count", rejected.toLong())
            span.setAttribute("accepted_count", normalized.size.toLong())

            if (normalized.isEmpty()) {
                log.warn { "All ${raw.size} signals rejected." }
                span.addEvent("all_signals_rejected")
                null
            } else {
                log.info { "Processed signals: ${normalized.size} accepted, $rejected rejected." }
                normalized
            }
        }
    }

    /**
     * Wraps the actual execution call (OKX)
     */
    private suspend fun stageExecute(decisions: AiTradeDecisionMap, invocation: Long, cid: String): Int? {
        return traceAndMeasure("execute_orders", cid, mapOf("decisionCount" to decisions.size)) { span ->
            try {
                // This is the external call to OKX/Exchange wrapper
                val results = execute.execute(decisions)

                val placed = results.count { it.action == AIAction.PLACED }
                val skipped = results.count { it.action == AIAction.SKIPPED }

                span.setAttribute("orders_placed", placed.toLong())
                span.setAttribute("orders_skipped", skipped.toLong())

                log.info { "Execution complete. Placed: $placed, Skipped: $skipped" }
                placed
            } catch (e: Exception) {
                handleStageError("execute", e, span)
                null
            }
        }
    }

    // =========================================================================
    // Helpers & Utilities
    // =========================================================================

    /**
     * Centralized Tracing and Metrics wrapper.
     * 1. Starts OTel Span
     * 2. Puts CorrelationID in MDC
     * 3. Records Micrometer Timer
     * 4. Handles Exceptions by recording them to Span
     */
    private suspend inline fun <T> traceAndMeasure(
        stage: String,
        cid: String,
        attrs: Map<String, Any> = emptyMap(),
        crossinline block: suspend (Span) -> T
    ): T {
        val spanBuilder = tracer.spanBuilder("$METRIC_CYCLE.$stage")
        attrs.forEach { (k, v) ->
            when (v) {
                is String -> spanBuilder.setAttribute(k, v)
                is Long -> spanBuilder.setAttribute(k, v)
                is Int -> spanBuilder.setAttribute(k, v.toLong())
                is Boolean -> spanBuilder.setAttribute(k, v)
            }
        }
        spanBuilder.setAttribute(MDC_CORRELATION_ID, cid)

        val span = spanBuilder.startSpan()

        // Use makeCurrent() to ensure child spans (in dependencies) are attached to this parent
        return ScopeUtil.withScope(span.makeCurrent()) {
            // Ensure MDC is present for logs inside this block
            withMdc(cid) {
                try {
                    meterRegistry.measureSuspend("$METRIC_CYCLE.$stage") {
                        block(span)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    span.recordException(e)
                    span.setStatus(StatusCode.ERROR, e.message ?: "-")
                    throw e
                } finally {
                    span.end()
                }
            }
        }
    }

    // Helper to manage MDC scope manually to guarantee logs have the ID
    private inline fun <T> withMdc(cid: String, block: () -> T): T {
        val previous = MDC.get(MDC_CORRELATION_ID)
        MDC.put(MDC_CORRELATION_ID, cid)
        try {
            return block()
        } finally {
            if (previous != null) {
                MDC.put(MDC_CORRELATION_ID, previous)
            } else {
                MDC.remove(MDC_CORRELATION_ID)
            }
        }
    }

    private fun handleStageError(stage: String, e: Exception, span: Span) {
        if (e is CancellationException) throw e
        meterRegistry.counter("$METRIC_CYCLE.$stage.error").increment()

        span.recordException(e)
        span.setStatus(StatusCode.ERROR, "Stage failed")

        log.error(e) { "Error in stage: $stage" }
    }

    private fun isPromptUnchanged(prompt: String): Boolean {
        val hash = hashPrompt(prompt)
        if (hash == lastPromptHash.get()) return true
        lastPromptHash.set(hash)
        return false
    }

    private fun hashPrompt(prompt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun recordValidationRejection(reason: String) {
        val tag = reason.take(50).replace(Regex("[^a-zA-Z0-9_\\-]"), "_").lowercase()
        meterRegistry.counter(METRIC_VALIDATION_REJECTED, "reason", tag).increment()
    }

    private fun errorResult(msg: String, promptSize: Int = 0) =
        UpdateCycleResult(false, "Error: $msg", 0, promptSize)

    // =========================================================================
    // Logging & Info Accessors
    // =========================================================================

    private fun logHeader(i: Long) {
        log.info { "╔══════════════════════════════════════════════════════" }
        log.info { "║ Update Cycle #$i" }
        log.info { "╚══════════════════════════════════════════════════════" }
    }

    private fun logFooter(i: Long, took: Long) {
        log.info { "═══ Cycle #$i completed in ${took}ms ═══\n" }
    }

    private fun logAiDecisionsSummary(decisions: AiTradeDecisionMap) {
        runCatching {
            log.info { "═══ AI Decisions (${decisions.size}) ═══" }
            decisions.forEach { (sym, env) ->
                log.info { "  • ${formatDecision(sym, env)}" }
            }
        }.onFailure { log.warn(it) { "Log summary failed" } }
    }

    private fun formatDecision(sym: String, env: AiTradeEnvelope): String {
        val a = env.args
        if (a.signal == AiSignal.HOLD) return "$sym: HOLD"

        return buildString {
            append("$sym: ${a.signal}")
            a.confidence?.let { append(" | Conf: ${"%.2f".format(Locale.ROOT, it)}") }
            a.leverage?.let { append(" | Lev: $it") }
            a.quantity?.let { append(" | Qty: ${"%.4f".format(Locale.ROOT, it)}") }
            a.riskUsd?.let { append(" | Risk: $${"%.2f".format(Locale.ROOT, it)}") }
        }
    }

    // Public Accessors - Wrapped to trace external OKX calls if these methods hit the API
    suspend fun getAccountInfo(): AccountInfo = traceAndMeasure("fetch_account_info", "read_only") {
        market.loadMarketState(symbols.map { it.asSymbol() }).account
    }

    suspend fun getPositions(): List<Position> = traceAndMeasure("fetch_positions", "read_only") {
        market.loadMarketState(symbols.map { it.asSymbol() }).positions
    }

    fun getSessionStartTime(): Long = sessionStartTime.get()
    fun getInvocationCount(): Long = invocationCount.get()
    fun getLastUpdateTime(): Long? = lastUpdateTime.get().takeIf { it > 0 }
}

data class UpdateCycleResult(
    val success: Boolean,
    val message: String,
    val executionTimeMs: Long,
    val promptSize: Int,
    val positionsPlaced: Int = 0
)

// Helper for AutoCloseable OTel Scope
private object ScopeUtil {
    inline fun <T> withScope(scope: io.opentelemetry.context.Scope, block: () -> T): T {
        return try {
            block()
        } finally {
            scope.close()
        }
    }
}
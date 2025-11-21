package ru.driics.aitrade.application.orchestrator

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CancellationException
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
import ru.driics.aitrade.common.traced
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.MarketDataPort
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
    }

    // State
    private val sessionStartTime = AtomicLong(clock.instant().toEpochMilli())
    private val invocationCount = AtomicLong(0L)
    private val lastUpdateTime = AtomicLong(0L)
    private val lastPromptHash = AtomicReference<String?>(null)

    // Components (lazy initialized if heavy, but here lightweight)
    private val normalizer = SignalNormalizer()

    suspend fun runOnce(): UpdateCycleResult {
        val invocation = invocationCount.incrementAndGet()
        val correlationId = CorrelationId.generate()

        logHeader(invocation)

        // Wrap entire cycle in trace & metric
        return tracer.traced(METRIC_CYCLE, {
            attr("cycleNumber", invocation)
            attr("autoExecute", autoExecute)
            attr("correlationId", correlationId)
        }) {
            // Using measureSuspend (from previous context) which returns T
            // We manually time the whole operation for the result object
            val start = System.currentTimeMillis()

            val cycleResult = meterRegistry.measureSuspend(
                METRIC_CYCLE,
                "autoExecute", autoExecute.toString()
            ) {
                executePipeline(invocation, correlationId)
            }

            val duration = System.currentTimeMillis() - start
            lastUpdateTime.set(clock.instant().toEpochMilli())

            logFooter(invocation, duration)

            cycleResult.copy(executionTimeMs = duration)
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

        // 6. Execute (Optional)
        val positionsPlaced = if (autoExecute) {
            stageExecute(finalDecisions, invocation, cid)
                ?: return errorResult("Execution failed after successful AI", prompt.length)
        } else {
            log.info { "Auto-execution disabled." }
            0
        }

        // 7. Success Event
        BusinessEventLogger.updateCycle(invocation, 0, finalDecisions.size, positionsPlaced, true)

        return UpdateCycleResult(true, "Success", 0, prompt.length, positionsPlaced)
    }

    // =========================================================================
    // Pipeline Stages
    // =========================================================================

    private suspend fun stageBuildPrompt(invocation: Long, cid: String): String? {
        return traceAndMeasure("build_prompt", cid) {
            try {
                build.execute(symbols, sessionStartTime.get(), invocation)
            } catch (e: Exception) {
                handleStageError("build_prompt", e)
                null
            }
        }
    }

    private suspend fun stageAiAnalysis(prompt: String, cid: String): AiAnalysisResponse? {
        return traceAndMeasure("ai_call", cid, mapOf("promptSize" to prompt.length)) {
            try {
                analyze.execute(prompt)
            } catch (e: Exception) {
                handleStageError("ai_call", e)
                null
            }
        }
    }

    private suspend fun stageParseResponse(response: String, promptSize: Int, cid: String): AiTradeDecisionMap? {
        return traceAndMeasure("parse", cid, mapOf("responseSize" to response.length)) {
            when (val result = schemaValidator.validateAndParse(response)) {
                is AiSchemaValidator.ValidationResult.Valid -> result.decisions
                is AiSchemaValidator.ValidationResult.Rejected -> {
                    recordValidationRejection(result.reason)
                    log.error { "AI schema rejected: ${result.reason}" }
                    BusinessEventLogger.error("ai_schema_rejected", IllegalArgumentException(result.reason), "response" to response.take(RESPONSE_LOG_LIMIT))
                    null
                }
            }
        }
    }

    private suspend fun stageGuardAndNormalize(raw: AiTradeDecisionMap, promptSize: Int, cid: String): AiTradeDecisionMap? {
        return traceAndMeasure("guard", cid, mapOf("rawCount" to raw.size)) {
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
                    }
                    is ConfidenceCalibrator.CalibrationResult.Rejected -> {
                        log.debug { "Signal rejected ($symbol): ${cal.reason}" }
                        rejected++
                    }
                }
            }

            if (normalized.isEmpty()) {
                log.warn { "All ${raw.size} signals rejected." }
                null
            } else {
                log.info { "Processed signals: ${normalized.size} accepted, $rejected rejected." }
                normalized
            }
        }
    }

    private suspend fun stageExecute(decisions: AiTradeDecisionMap, invocation: Long, cid: String): Int? {
        return traceAndMeasure("execute", cid, mapOf("count" to decisions.size)) {
            try {
                val results = execute.execute(decisions)
                log.info { "Execution complete. Processed ${results.size} results." }
                results.count { it.action == AIAction.PLACED }
            } catch (e: Exception) {
                handleStageError("execute", e)
                null
            }
        }
    }

    // =========================================================================
    // Helpers & Utilities
    // =========================================================================

    private suspend inline fun <T> traceAndMeasure(
        stage: String,
        cid: String,
        attrs: Map<String, Any> = emptyMap(),
        crossinline block: suspend () -> T
    ): T = tracer.traced("$METRIC_CYCLE.$stage", {
        attr("correlationId", cid)
        attrs.forEach { (k, v) ->
            when(v) {
                is String -> attr(k, v)
                is Long -> attr(k, v)
                is Int -> attr(k, v.toLong())
                is Boolean -> attr(k, v)
            }
        }
    }) {
        meterRegistry.measureSuspend("$METRIC_CYCLE.$stage") {
            block()
        }
    }

    private fun handleStageError(stage: String, e: Exception) {
        if (e is CancellationException) throw e
        meterRegistry.counter("$METRIC_CYCLE.$stage.error").increment()
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

    // Public Accessors for Health Checks / Status
    suspend fun getAccountInfo(): AccountInfo = market.loadMarketState(symbols.map { it.asSymbol() }).account
    suspend fun getPositions(): List<Position> = market.loadMarketState(symbols.map { it.asSymbol() }).positions
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
package ru.driics.aitrade.application.orchestrator

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CancellationException
import org.slf4j.MDC
import ru.driics.aitrade.application.ai.AiSchemaValidator
import ru.driics.aitrade.application.ai.AiSchemaValidator.ValidationResult
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.ai.ConfidenceCalibrator.CalibrationResult
import ru.driics.aitrade.application.ai.SignalNormalizer
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import ru.driics.aitrade.application.usecase.PromptResult
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.common.logging.CorrelationId
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.common.measureSuspend
import ru.driics.aitrade.application.risk.KillSwitchState
import ru.driics.aitrade.config.RiskGateProperties
import ru.driics.aitrade.domain.risk.RiskContext
import ru.driics.aitrade.domain.journal.JournaledPnlSnapshot
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.DecisionLogSink
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.TradingMetricsService
import ru.driics.aitrade.domain.types.TradeResult
import ru.driics.aitrade.domain.types.asSymbol
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class UpdateCycleOrchestrator(
    private val config: OrchestratorConfig, private val useCases: UseCases, private val infrastructure: Infrastructure
) {
    // =========================================================================
    // Configuration & Dependencies (grouped)
    // =========================================================================

    data class OrchestratorConfig(
        val symbols: List<String>, val autoExecute: Boolean
    )

    data class UseCases(
        val build: BuildPromptUseCase, val analyze: AnalyzePromptUseCase, val execute: ExecuteAiDecisionsUseCase
    )

    data class Infrastructure(
        val market: MarketDataPort,
        val trading: TradingPort,
        val killSwitchState: KillSwitchState,
        val riskGateProperties: RiskGateProperties,
        val meterRegistry: MeterRegistry,
        val tradingMetrics: TradingMetricsService,
        val schemaValidator: AiSchemaValidator,
        val confidenceCalibrator: ConfidenceCalibrator,
        val tracer: Tracer,
        val clock: Clock,
        val tradeJournal: TradeJournalPort,
        val decisionLogSink: DecisionLogSink? = null
    )

    // =========================================================================
    // State
    // =========================================================================

    private val sessionStartTime: Long = infrastructure.clock.millis()
    private val invocationCount = AtomicLong(0L)
    private val lastUpdateTime = AtomicLong(0L)
    private val lastPromptHash = AtomicReference<String?>(null)

    private val normalizer = SignalNormalizer()

    // =========================================================================
    // Public API
    // =========================================================================

    suspend fun runOnce(): UpdateCycleResult {
        val invocation = invocationCount.incrementAndGet()
        val correlationId = CorrelationId.generate()
        val startTime = System.currentTimeMillis()

        return traced(Stage.RUN_ONCE, correlationId) { span ->
            span.setAttribute(Attrs.CYCLE_NUMBER, invocation)
            span.setAttribute(Attrs.AUTO_EXECUTE, config.autoExecute)

            logCycleHeader(invocation)

            val result = try {
                executePipeline(invocation, correlationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Critical failure in update cycle" }
                span.recordException(e)
                span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
                throw e
            }

            val duration = System.currentTimeMillis() - startTime

            span.apply {
                setAttribute(Attrs.SUCCESS, result.success)
                setAttribute(Attrs.POSITIONS_PLACED, result.positionsPlaced.toLong())
                setAttribute(Attrs.MESSAGE, result.message)
                if (!result.success) setStatus(StatusCode.ERROR, result.message)
            }

            if (result.success) {
                lastUpdateTime.set(infrastructure.clock.millis())
            }

            logCycleFooter(invocation, duration)

            result.copy(executionTimeMs = duration)
        }
    }

    suspend fun getAccountInfo(): AccountInfo = traced(Stage.FETCH_ACCOUNT, READ_ONLY_CID) {
        loadMarketState().account
    }

    suspend fun getPositions(): List<Position> = traced(Stage.FETCH_POSITIONS, READ_ONLY_CID) {
        loadMarketState().positions
    }

    fun getSessionStartTime(): Long = sessionStartTime
    fun getInvocationCount(): Long = invocationCount.get()
    fun getLastUpdateTime(): Long? = lastUpdateTime.get().takeIf { it > 0 }

    // =========================================================================
    // Pipeline Execution
    // =========================================================================

    private suspend fun executePipeline(invocation: Long, cid: String): UpdateCycleResult {
        // 1. Build Prompt (P2: also yields the single market snapshot for this cycle)
        val promptResult = stageBuildPrompt(invocation, cid).getOrElse { return it.toResult() }
        val prompt = promptResult.prompt
        val promptHash = prompt.sha256()

        // 2. Check for changes. S5: this is a PURE check — the dedup hash is advanced only after a
        // fully successful cycle (step 7), so a failure in any stage below does not leave the hash
        // advanced and silently skip the next identical prompt.
        if (isPromptUnchanged(promptHash)) {
            log.info { "Prompt unchanged, skipping analysis" }
            Span.current().setAttribute(Attrs.SKIPPED, true)
            return UpdateCycleResult.skipped(prompt.length)
        }

        // 3. AI Analysis
        val aiResponse = stageAiAnalysis(prompt, cid).getOrElse { return it.toResult(prompt.length) }

        // 4. Parse & Validate Response
        val rawDecisions = stageParseResponse(aiResponse, cid).getOrElse { return it.toResult(prompt.length) }

        // 4b. Audit: persist what the AI decided this cycle (optional sink, fail-safe, wall-clock stamped).
        infrastructure.decisionLogSink?.record(rawDecisions, promptResult.marketState.timestamp)

        // 5. Apply Guardrails & Normalize
        val finalDecisions = stageGuardAndNormalize(rawDecisions, cid).getOrElse { return it.toResult(prompt.length) }

        logDecisionsSummary(finalDecisions)

        // 6. Execute Orders (P2: thread the build-stage snapshot in instead of re-loading)
        val positionsPlaced = stageExecute(finalDecisions, promptResult.marketState, cid)
            .getOrElse { return it.toResult(prompt.length) }

        // 7. Record Success. S5: advance the dedup hash ONLY now that the entire cycle has
        // succeeded. Any stage failure above returns early before this point, so the next identical
        // prompt is retried rather than skipped. Manual mode (autoExecute=false, placed=0) reaches
        // here and is intentionally treated as a successful cycle for dedup purposes.
        lastPromptHash.set(promptHash)

        BusinessEventLogger.updateCycle(
            cycleNumber = invocation,
            durationMs = 0L,
            symbolsProcessed = finalDecisions.size,
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

    // =========================================================================
    // Pipeline Stages
    // =========================================================================

    private suspend fun stageBuildPrompt(invocation: Long, cid: String): StageResult<PromptResult> =
        runStage(Stage.BUILD_PROMPT, cid) {
            useCases.build.execute(config.symbols, sessionStartTime, invocation)
        }

    private suspend fun stageAiAnalysis(prompt: String, cid: String): StageResult<String> =
        runStage(Stage.AI_CALL, cid, mapOf(Attrs.PROMPT_SIZE to prompt.length)) { span ->
            val response = useCases.analyze.execute(prompt)
            if (response.isSuccess) {
                response.response
            } else {
                span.setStatus(StatusCode.ERROR, response.errorMessage ?: "AI failed")
                throw StageException("AI analysis failed: ${response.errorMessage}")
            }
        }

    private suspend fun stageParseResponse(response: String, cid: String): StageResult<AiTradeDecisionMap> =
        runStage(Stage.PARSE_RESPONSE, cid, mapOf(Attrs.RESPONSE_SIZE to response.length)) { span ->
            when (val result = infrastructure.schemaValidator.validateAndParse(response)) {
                is ValidationResult.Valid -> result.decisions
                is ValidationResult.Rejected -> {
                    recordValidationRejection(result.reason)
                    span.setStatus(StatusCode.ERROR, "Schema validation failed")
                    span.setAttribute(Attrs.REJECTION_REASON, result.reason)

                    log.error { "AI schema rejected: ${result.reason}" }
                    BusinessEventLogger.error(
                        "ai_schema_rejected",
                        IllegalArgumentException(result.reason),
                        "response" to response.take(Limits.RESPONSE_LOG)
                    )

                    throw StageException("AI response validation failed")
                }
            }
        }

    private suspend fun stageGuardAndNormalize(
        raw: AiTradeDecisionMap, cid: String
    ): StageResult<AiTradeDecisionMap> =
        runStage(Stage.GUARD_NORMALIZE, cid, mapOf(Attrs.RAW_COUNT to raw.size)) { span ->
            val accepted = mutableMapOf<String, AiTradeEnvelope>()
            var rejected = 0

            for ((symbol, envelope) in raw) {
                val normalized = normalizer.normalize(envelope.args)?.toAiTradeSignalArgs()
                if (normalized == null) {
                    rejected++
                    continue
                }

                when (val calibration = infrastructure.confidenceCalibrator.shouldAccept(normalized)) {
                    is CalibrationResult.Accepted -> {
                        accepted[symbol] = AiTradeEnvelope(normalized)
                        infrastructure.tradingMetrics.recordAiSignal(
                            symbol = symbol,
                            signal = normalized.signal.toString(),
                            confidence = normalized.confidence?.toDouble() ?: 0.0
                        )
                    }

                    is CalibrationResult.Rejected -> {
                        log.debug { "Signal rejected ($symbol): ${calibration.reason}" }
                        rejected++
                    }
                }
            }

            span.setAttribute(Attrs.REJECTED_COUNT, rejected.toLong())
            span.setAttribute(Attrs.ACCEPTED_COUNT, accepted.size.toLong())

            if (accepted.isEmpty()) {
                log.warn { "All ${raw.size} signals rejected" }
                span.addEvent("all_signals_rejected")
                throw StageException("All signals rejected by guardrails")
            }

            log.info { "Signals processed: ${accepted.size} accepted, $rejected rejected" }
            accepted
        }

    private suspend fun stageExecute(
        decisions: AiTradeDecisionMap, marketState: MarketState, cid: String
    ): StageResult<Int> {
        if (!config.autoExecute) {
            log.info { "Auto-execution disabled" }
            Span.current().setAttribute(Attrs.EXECUTION_SKIPPED, "manual_mode")
            return StageResult.success(0)
        }

        return runStage(Stage.EXECUTE_ORDERS, cid, mapOf(Attrs.DECISION_COUNT to decisions.size)) { span ->
            // Build RiskContext once per cycle.
            // S1: PnL read failure fails CLOSED when risk gating is enabled — we cannot confirm
            // we're within the daily-loss cap, so we refuse to place orders this cycle (throwing
            // here surfaces a failed StageResult, so the cycle is not marked successful and the
            // dedup hash is not advanced — the next cycle retries). If risk gating is disabled,
            // PnL is irrelevant to execution, so we proceed with ZERO.
            val pnl = when (val result = infrastructure.trading.getTodaysRealizedPnlUsd(infrastructure.clock.instant())) {
                is TradeResult.Success -> result.value
                is TradeResult.Failure -> {
                    if (infrastructure.riskGateProperties.enabled) {
                        infrastructure.meterRegistry.counter(Metrics.PNL_READ_FAILED, "action", "skip_cycle").increment()
                        log.error { "Fail-closed: today's realized PnL unreadable (${result.message}); skipping execution this cycle" }
                        throw StageException("Fail-closed: PnL read failed (${result.message})")
                    }
                    log.warn { "PnL read failed but risk gating disabled; proceeding with ZERO: ${result.message}" }
                    BigDecimal.ZERO
                }
            }
            // P2: open-position count comes from the cycle's single market snapshot, not a re-load.
            val openPositionsCount = marketState.positions.size
            val killSnap = infrastructure.killSwitchState.snapshot()
            val riskContext = RiskContext(
                openPositionsCount = openPositionsCount,
                todaysRealizedPnlUsd = pnl,
                killSwitch = killSnap,
            )

            infrastructure.tradeJournal.recordPnlSnapshot(
                JournaledPnlSnapshot(
                    timestampMs = infrastructure.clock.instant().toEpochMilli(),
                    cycle = marketState.invocationCount,
                    accountValue = marketState.account.accountValue,
                    availableCash = marketState.account.availableCash,
                    totalReturn = marketState.account.totalReturn,
                    realizedPnlToday = pnl,
                    openPositionsCount = openPositionsCount,
                )
            )

            val results = useCases.execute.execute(decisions, riskContext, marketState)

            val placed = results.count { it.action == AIAction.PLACED }
            val skipped = results.count { it.action == AIAction.SKIPPED }

            span.setAttribute(Attrs.ORDERS_PLACED, placed.toLong())
            span.setAttribute(Attrs.ORDERS_SKIPPED, skipped.toLong())

            log.info { "Execution complete: $placed placed, $skipped skipped" }
            placed
        }
    }

    // =========================================================================
    // Tracing Infrastructure
    // =========================================================================

    private suspend inline fun <T> traced(
        stage: Stage,
        correlationId: String,
        attrs: Map<String, Any> = emptyMap(),
        crossinline block: suspend (Span) -> T
    ): T {
        val span = infrastructure.tracer.spanBuilder("${Metrics.CYCLE_PREFIX}.${stage.value}").apply {
                setAttribute(Attrs.CORRELATION_ID, correlationId)
                attrs.forEach { (key, value) -> setTypedAttribute(key, value) }
            }.startSpan()

        return span.makeCurrent().use {
            withMdc(correlationId) {
                try {
                    infrastructure.meterRegistry.measureSuspend("${Metrics.CYCLE_PREFIX}.${stage.value}") {
                        block(span)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    span.recordException(e)
                    span.setStatus(StatusCode.ERROR, e.message ?: "Unknown")
                    throw e
                } finally {
                    span.end()
                }
            }
        }
    }

    private suspend inline fun <T> runStage(
        stage: Stage,
        correlationId: String,
        attrs: Map<String, Any> = emptyMap(),
        crossinline block: suspend (Span) -> T
    ): StageResult<T> = try {
        traced(stage, correlationId, attrs) { span ->
            StageResult.success(block(span))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: StageException) {
        StageResult.failure(e.message ?: "Stage failed")
    } catch (e: Exception) {
        infrastructure.meterRegistry.counter("${Metrics.CYCLE_PREFIX}.${stage.value}.error").increment()
        log.error(e) { "Error in stage: ${stage.value}" }
        StageResult.failure("${stage.value} failed: ${e.message}")
    }

    private inline fun <T> withMdc(correlationId: String, block: () -> T): T {
        val previous = MDC.get(Attrs.CORRELATION_ID)
        MDC.put(Attrs.CORRELATION_ID, correlationId)
        return try {
            block()
        } finally {
            if (previous != null) MDC.put(Attrs.CORRELATION_ID, previous)
            else MDC.remove(Attrs.CORRELATION_ID)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private suspend fun loadMarketState(): MarketState =
        infrastructure.market.loadMarketState(config.symbols.map { it.asSymbol() })

    /** Pure check (S5). The hash is advanced only on a fully successful cycle, never here. */
    private fun isPromptUnchanged(promptHash: String): Boolean = promptHash == lastPromptHash.get()

    private fun recordValidationRejection(reason: String) {
        // S9: collapse the free-text reason to a small fixed code so the metric tag cardinality stays
        // bounded (the detailed reason is still logged in stageParseResponse).
        infrastructure.meterRegistry
            .counter(Metrics.VALIDATION_REJECTED, "reason", classifyValidationRejection(reason))
            .increment()
    }

    // =========================================================================
    // Logging
    // =========================================================================

    private fun logCycleHeader(cycle: Long) {
        log.info { "╔══════════════════════════════════════════════════════" }
        log.info { "║ Update Cycle #$cycle" }
        log.info { "╚══════════════════════════════════════════════════════" }
    }

    private fun logCycleFooter(cycle: Long, durationMs: Long) {
        log.info { "═══ Cycle #$cycle completed in ${durationMs}ms ═══\n" }
    }

    private fun logDecisionsSummary(decisions: AiTradeDecisionMap) {
        runCatching {
            log.info { "═══ AI Decisions (${decisions.size}) ═══" }
            decisions.forEach { (symbol, envelope) ->
                log.info { "  • ${envelope.args.formatFor(symbol)}" }
            }
        }.onFailure { log.warn(it) { "Failed to log decisions summary" } }
    }

    // =========================================================================
    // Extensions
    // =========================================================================

    private fun io.opentelemetry.api.trace.SpanBuilder.setTypedAttribute(
        key: String, value: Any
    ): io.opentelemetry.api.trace.SpanBuilder = apply {
        when (value) {
            is String -> setAttribute(key, value)
            is Long -> setAttribute(key, value)
            is Int -> setAttribute(key, value.toLong())
            is Boolean -> setAttribute(key, value)
        }
    }

    private fun AiTradeSignalArgs.formatFor(symbol: String): String {
        if (signal == AiSignal.HOLD) return "$symbol: HOLD"

        return buildString {
            append("$symbol: $signal")
            confidence?.let { append(" | Conf: %.2f".format(it)) }
            leverage?.let { append(" | Lev: $it") }
            quantity?.let { append(" | Qty: %.4f".format(it)) }
            riskUsd?.let { append(" | Risk: $%.2f".format(it)) }
        }
    }

    private fun String.sha256(): String = MESSAGE_DIGEST.get().run {
        reset()
        digest(this@sha256.toByteArray())
    }.toHexString()

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    // =========================================================================
    // Types
    // =========================================================================

    private sealed class StageResult<out T> {
        data class Success<T>(val value: T) : StageResult<T>()
        data class Failure(val message: String) : StageResult<Nothing>()

        companion object {
            fun <T> success(value: T): StageResult<T> = Success(value)
            fun failure(message: String): StageResult<Nothing> = Failure(message)
        }

        // onFailure must diverge (every caller does a non-local `return`), so its result type is Nothing.
        // That keeps T in out-position only — preserving the covariance — and removes the old `as T` cast.
        inline fun getOrElse(onFailure: (Failure) -> Nothing): T = when (this) {
            is Success -> value
            is Failure -> onFailure(this)
        }

        fun toResult(promptSize: Int = 0): UpdateCycleResult = when (this) {
            is Success -> error("Cannot convert Success to error result")
            is Failure -> UpdateCycleResult(
                success = false, message = "Error: $message", executionTimeMs = 0, promptSize = promptSize
            )
        }
    }

    private class StageException(message: String) : Exception(message)

    private enum class Stage(val value: String) {
        RUN_ONCE("run_once"), BUILD_PROMPT("build_prompt"), AI_CALL("ai_call"), PARSE_RESPONSE("parse_response"), GUARD_NORMALIZE(
            "guard_normalize"
        ),
        EXECUTE_ORDERS("execute_orders"), FETCH_ACCOUNT("fetch_account_info"), FETCH_POSITIONS("fetch_positions")
    }

    // =========================================================================
    // Constants
    // =========================================================================

    private object Metrics {
        const val CYCLE_PREFIX = "update.cycle"
        const val VALIDATION_REJECTED = "ai.response.validation.rejected"
        const val PNL_READ_FAILED = "risk.pnl.read_failed"
    }

    private object Attrs {
        const val CORRELATION_ID = "correlationId"
        const val CYCLE_NUMBER = "cycleNumber"
        const val AUTO_EXECUTE = "autoExecute"
        const val SUCCESS = "success"
        const val POSITIONS_PLACED = "positionsPlaced"
        const val MESSAGE = "message"
        const val SKIPPED = "skipped"
        const val PROMPT_SIZE = "promptSize"
        const val RESPONSE_SIZE = "responseSize"
        const val REJECTION_REASON = "rejection_reason"
        const val RAW_COUNT = "rawCount"
        const val REJECTED_COUNT = "rejected_count"
        const val ACCEPTED_COUNT = "accepted_count"
        const val DECISION_COUNT = "decisionCount"
        const val ORDERS_PLACED = "orders_placed"
        const val ORDERS_SKIPPED = "orders_skipped"
        const val EXECUTION_SKIPPED = "execution_skipped"
    }

    private object Limits {
        const val RESPONSE_LOG = 500
    }

    private companion object {
        val log = logger<UpdateCycleOrchestrator>()
        val TAG_SANITIZER_REGEX = Regex("[^a-zA-Z0-9_\\-]")
        const val READ_ONLY_CID = "read_only"

        // ThreadLocal for thread-safe MessageDigest reuse
        val MESSAGE_DIGEST: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
            MessageDigest.getInstance("SHA-256")
        }
    }
}

/**
 * Maps a free-text AI schema-rejection reason to a small, stable metric code (S9). Keeps the
 * "ai.response.validation.rejected" metric's `reason` tag bounded to a known set instead of an
 * unbounded sanitized string. The full reason is preserved in logs, not the metric.
 */
internal fun classifyValidationRejection(reason: String): String = when {
    reason.contains("Invalid JSON", ignoreCase = true) -> "json_invalid"
    reason.contains("Failed to parse", ignoreCase = true) -> "parse_failed"
    reason.contains("Empty response", ignoreCase = true) -> "empty"
    reason.contains("Bean validation", ignoreCase = true) -> "bean_validation"
    reason.contains("Coin mismatch", ignoreCase = true) -> "coin_mismatch"
    reason.contains("Confidence", ignoreCase = true) -> "confidence_range"
    reason.contains("Leverage", ignoreCase = true) -> "leverage_range"
    reason.contains("Profit target", ignoreCase = true) ||
        reason.contains("Stop loss", ignoreCase = true) -> "price_range"
    reason.contains("Quantity", ignoreCase = true) -> "quantity_range"
    reason.contains("Risk USD", ignoreCase = true) -> "risk_range"
    reason.contains("requires quantity or riskUsd", ignoreCase = true) -> "missing_size"
    reason.contains("Invalid signal", ignoreCase = true) -> "signal_invalid"
    reason.contains("All signals rejected", ignoreCase = true) -> "all_signals_rejected"
    else -> "other"
}

// =========================================================================
// Result Type
// =========================================================================

data class UpdateCycleResult(
    val success: Boolean,
    val message: String,
    val executionTimeMs: Long,
    val promptSize: Int,
    val positionsPlaced: Int = 0
) {
    companion object {
        fun skipped(promptSize: Int) = UpdateCycleResult(
            success = true, message = "Skipped (unchanged)", executionTimeMs = 0, promptSize = promptSize
        )
    }
}
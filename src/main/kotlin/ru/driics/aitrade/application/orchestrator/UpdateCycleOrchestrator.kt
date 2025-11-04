package ru.driics.aitrade.application.orchestrator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.model.AccountInfo
import ru.driics.aitrade.model.AiTradeDecisionMap
import ru.driics.aitrade.model.Position
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates the three-stage update cycle:
 * 1. Build prompt from market data
 * 2. Analyze with AI
 * 3. Execute trading decisions (if enabled)
 */
class UpdateCycleOrchestrator(
    private val build: BuildPromptUseCase,
    private val analyze: AnalyzePromptUseCase,
    private val execute: ExecuteAiDecisionsUseCase,
    private val market: MarketDataPort,
    private val autoExecute: Boolean,
    private val symbols: List<String>
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val mapper = jacksonObjectMapper()
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val invocationCount = AtomicLong(0L)
    private val lastUpdateTime = AtomicLong(0L)

    suspend fun runOnce(): UpdateCycleResult {
        val invocation = invocationCount.incrementAndGet()
        val startMs = System.currentTimeMillis()

        log.info { "╔══════════════════════════════════════════════════════" }
        log.info { "║ Update Cycle #$invocation" }
        log.info { "╚══════════════════════════════════════════════════════" }

        // Stage 1: Build prompt
        val prompt = try {
            build.execute(symbols, sessionStartTime.get(), invocation)
        } catch (e: Exception) {
            log.error(e) { "Failed to build prompt" }
            return UpdateCycleResult(
                success = false,
                message = "Error: Failed to build prompt - ${e.message}",
                executionTimeMs = System.currentTimeMillis() - startMs,
                promptSize = 0
            )
        }

        // Stage 2: AI analysis
        val aiResult = try {
            analyze.execute(prompt)
        } catch (e: Exception) {
            log.error(e) { "Failed to analyze prompt" }
            return UpdateCycleResult(
                success = false,
                message = "Error: Failed to analyze prompt - ${e.message}",
                executionTimeMs = System.currentTimeMillis() - startMs,
                promptSize = prompt.length
            )
        }

        if (!aiResult.isSuccess) {
            log.error { "AI analysis failed: ${aiResult.errorMessage}" }
            return UpdateCycleResult(
                success = false,
                message = "Error: AI analysis failed - ${aiResult.errorMessage}",
                executionTimeMs = System.currentTimeMillis() - startMs,
                promptSize = prompt.length
            )
        }

        log.info { "AI analysis completed successfully" }
        logAiDecisionsSummary(aiResult.response)

        var executionResults: List<ru.driics.aitrade.model.AiTradeExecutionResult>? = null

        // Stage 3: Execute decisions (if enabled)
        if (autoExecute) {
            try {
                executionResults = execute.execute(aiResult.response)
                log.info { "Trade execution completed: ${executionResults.size} decisions processed" }
            } catch (e: Exception) {
                log.error(e) { "Failed to execute AI decisions" }
                return UpdateCycleResult(
                    success = false,
                    message = "Warning: AI succeeded but execution failed - ${e.message}",
                    executionTimeMs = System.currentTimeMillis() - startMs,
                    promptSize = prompt.length
                )
            }
        } else {
            log.info { "Auto-execution disabled, skipping trade placement" }
        }

        lastUpdateTime.set(System.currentTimeMillis())
        val executionTimeMs = lastUpdateTime.get() - startMs

        log.info { "═══ Update cycle #$invocation completed in ${executionTimeMs}ms ═══\n" }

        return UpdateCycleResult(
            success = true,
            message = "Success",
            executionTimeMs = executionTimeMs,
            promptSize = prompt.length,
            positionsPlaced = executionResults?.count { it.action == ru.driics.aitrade.model.AIAction.PLACED } ?: 0
        )
    }

    suspend fun getAccountInfo(): AccountInfo = market.loadMarketState(symbols).account

    suspend fun getPositions(): List<Position> = market.loadMarketState(symbols).positions

    fun getSessionStartTime(): Long = sessionStartTime.get()

    fun getInvocationCount(): Long = invocationCount.get()

    fun getLastUpdateTime(): Long? = lastUpdateTime.get().takeIf { it > 0 }

    /**
     * Logs a compact summary of AI decisions instead of verbose JSON.
     */
    private fun logAiDecisionsSummary(aiJson: String) {
        try {
            val decisions: AiTradeDecisionMap = mapper.readValue(aiJson)

            log.info { "═══ AI Decisions Summary (${decisions.size} symbols) ═══" }

            decisions.forEach { (symbol, envelope) ->
                val args = envelope.args
                val signal = args.signal.uppercase()
                val confidence = args.confidence?.let { String.format("%.2f", it.toDouble()) } ?: "N/A"
                val leverage = args.leverage ?: "N/A"

                val details = buildString {
                    append("$symbol: $signal")
                    if (signal != "HOLD") {
                        append(" | Confidence: $confidence")
                        append(" | Leverage: $leverage")
                        args.quantity?.let { append(" | Qty: ${String.format("%.4f", it.toDouble())}") }
                        args.riskUsd?.let { append(" | Risk: $${String.format("%.2f", it.toDouble())}") }
                    }
                }

                log.info { "  • $details" }
            }

        } catch (e: Exception) {
            log.warn { "Could not parse AI decisions for summary: ${e.message}" }
            log.debug { "AI response (first 200 chars): ${aiJson.take(200)}" }
        }
    }
}

data class UpdateCycleResult(
    val success: Boolean,
    val message: String,
    val executionTimeMs: Long,
    val promptSize: Int,
    val positionsPlaced: Int = 0
)
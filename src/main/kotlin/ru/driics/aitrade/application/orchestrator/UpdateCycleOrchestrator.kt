package ru.driics.aitrade.application.orchestrator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase
import ru.driics.aitrade.common.timedSuspend
import ru.driics.aitrade.domain.model.AIAction
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.AiTradeDecisionMap
import ru.driics.aitrade.domain.model.AiTradeExecutionResult
import ru.driics.aitrade.domain.model.Position
import ru.driics.aitrade.domain.types.asSymbol
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
    private val clock: Clock
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val mapper = jacksonObjectMapper()
    private val sessionStartTime = AtomicLong(clock.instant().toEpochMilli())
    private val invocationCount = AtomicLong(0L)
    private val lastUpdateTime = AtomicLong(0L)
    private val lastPromptHash = AtomicReference<String?>(null)

    suspend fun runOnce(): UpdateCycleResult {
        val invocation = invocationCount.incrementAndGet()

        log.info { "╔══════════════════════════════════════════════════════" }
        log.info { "║ Update Cycle #$invocation" }
        log.info { "╚══════════════════════════════════════════════════════" }

        val (result, took) = meterRegistry.timedSuspend(
            "update.cycle",
            "autoExecute", autoExecute.toString()
        ) {
            doRunOnce(invocation)
        }

        lastUpdateTime.set(clock.instant().toEpochMilli())

        log.info { "═══ Update cycle #$invocation completed in ${took}ms ═══\n" }

        return result.copy(executionTimeMs = took)
    }

    private suspend fun doRunOnce(invocation: Long): UpdateCycleResult {
        // Stage 1: Build prompt
        val prompt = try {
            build.execute(symbols, sessionStartTime.get(), invocation)
        } catch (e: Exception) {
            log.error(e) { "Failed to build prompt" }
            return UpdateCycleResult(
                success = false,
                message = "Error: Failed to build prompt - ${e.message}",
                executionTimeMs = 0,
                promptSize = 0
            )
        }

        val currentHash = hashPrompt(prompt)
        val previousHash = lastPromptHash.get()
        if (currentHash == previousHash) {
            log.info { "Prompt unchanged from previous cycle, skipping AI analysis" }
            return UpdateCycleResult(
                success = true,
                message = "Skipped (prompt unchanged)",
                executionTimeMs = 0,
                promptSize = prompt.length,
                positionsPlaced = 0
            )
        }
        lastPromptHash.set(currentHash)

        // Stage 2: AI analysis
        val aiResult = try {
            analyze.execute(prompt)
        } catch (e: Exception) {
            log.error(e) { "Failed to analyze prompt" }
            return UpdateCycleResult(
                success = false,
                message = "Error: Failed to analyze prompt - ${e.message}",
                executionTimeMs = 0,
                promptSize = prompt.length
            )
        }

        if (!aiResult.isSuccess) {
            log.error { "AI analysis failed: ${aiResult.errorMessage}" }
            return UpdateCycleResult(
                success = false,
                message = "Error: AI analysis failed - ${aiResult.errorMessage}",
                executionTimeMs = 0,
                promptSize = prompt.length
            )
        }

        log.info { "AI analysis completed successfully" }

        val decisions: AiTradeDecisionMap = try {
            mapper.readValue(aiResult.response)
        } catch (e: Exception) {
            log.error(e) { "Failed to parse AI decisions" }
            return UpdateCycleResult(
                success = false,
                message = "Error: Failed to parse AI decisions - ${e.message}",
                executionTimeMs = 0,
                promptSize = prompt.length
            )
        }

        logAiDecisionsSummary(decisions)

        var executionResults: List<AiTradeExecutionResult>? = null

        // Stage 3: Execute decisions (if enabled)
        if (autoExecute) {
            try {
                executionResults = execute.execute(decisions)
                log.info { "Trade execution completed: ${executionResults.size} decisions processed" }
            } catch (e: Exception) {
                log.error(e) { "Failed to execute AI decisions" }
                return UpdateCycleResult(
                    success = false,
                    message = "Warning: AI succeeded but execution failed - ${e.message}",
                    executionTimeMs = 0,
                    promptSize = prompt.length
                )
            }
        } else {
            log.info { "Auto-execution disabled, skipping trade placement" }
        }

        return UpdateCycleResult(
            success = true,
            message = "Success",
            executionTimeMs = 0,
            promptSize = prompt.length,
            positionsPlaced = executionResults?.count { it.action == AIAction.PLACED } ?: 0
        )
    }

    suspend fun getAccountInfo(): AccountInfo = market.loadMarketState(symbols.map { it.asSymbol() }).account

    suspend fun getPositions(): List<Position> = market.loadMarketState(symbols.map { it.asSymbol() }).positions

    fun getSessionStartTime(): Long = sessionStartTime.get()

    fun getInvocationCount(): Long = invocationCount.get()

    fun getLastUpdateTime(): Long? = lastUpdateTime.get().takeIf { it > 0 }

    private fun logAiDecisionsSummary(decisions: AiTradeDecisionMap) {
        try {
            log.info { "═══ AI Decisions Summary (${decisions.size} symbols) ═══" }

            decisions.forEach { (symbol, envelope) ->
                val args = envelope.args
                val signal = args.signal.name
                val confidence = args.confidence?.let { String.format(Locale.ROOT, "%.2f", it.toDouble()) } ?: "N/A"
                val leverage = args.leverage ?: "N/A"

                val details = buildString {
                    append("$symbol: $signal")
                    if (signal != "HOLD") {
                        append(" | Confidence: $confidence")
                        append(" | Leverage: $leverage")
                        args.quantity?.let { append(" | Qty: ${String.format(Locale.ROOT, "%.4f", it.toDouble())}") }
                        args.riskUsd?.let { append(" | Risk: $${String.format(Locale.ROOT, "%.2f", it.toDouble())}") }
                    }
                }

                log.info { "  • $details" }
            }
        } catch (e: Exception) {
            log.warn { "Could not log AI decisions summary: ${e.message}" }
        }
    }

    private fun hashPrompt(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(prompt.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

data class UpdateCycleResult(
    val success: Boolean,
    val message: String,
    val executionTimeMs: Long,
    val promptSize: Int,
    val positionsPlaced: Int = 0
)
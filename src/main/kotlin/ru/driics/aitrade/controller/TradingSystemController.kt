package ru.driics.aitrade.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.config.PromptProperties
import ru.driics.aitrade.config.TradingProperties
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * REST controller for trading system monitoring and control.
 * Follows Clean Architecture: depends only on orchestrator and configuration.
 */
@RestController
@RequestMapping("/api/trading")
class TradingSystemController(
    private val orchestrator: UpdateCycleOrchestrator,
    private val tradingProperties: TradingProperties,
    private val promptProperties: PromptProperties
) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("UTC"))
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<HealthResponse> {
        val now = Instant.now().toEpochMilli()
        val sessionStart = orchestrator.getSessionStartTime()
        val uptimeSeconds = (now - sessionStart) / 1000

        val response = HealthResponse(
            status = "UP",
            message = "AI Trading System is running and healthy",
            timestamp = now,
            uptime = formatDuration(uptimeSeconds),
            lastUpdateTime = orchestrator.getLastUpdateTime(),
            invocationCount = orchestrator.getInvocationCount(),
            sessionStartTime = sessionStart
        )

        log.debug { "Health check - Status: UP, Invocations: ${response.invocationCount}" }
        return ResponseEntity.ok(response)
    }

    @PostMapping("/update")
    fun triggerUpdate(request: HttpServletRequest): ResponseEntity<Any> {
        log.info { "Manual update triggered from ${request.remoteAddr}" }

        return try {
            val result = runBlocking { orchestrator.runOnce() }

            if (!result.success) {
                val errorResponse = ErrorResponse(
                    status = "error",
                    message = result.message,
                    timestamp = System.currentTimeMillis(),
                    errorDetails = "Update execution failed. Check logs for details.",
                    path = request.requestURI
                )
                log.warn { "Update failed in ${result.executionTimeMs}ms: ${result.message}" }
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
            }

            // Fetch current data for response
            val account = runBlocking { orchestrator.getAccountInfo() }
            val positions = runBlocking { orchestrator.getPositions() }

            val response = UpdateResponse(
                status = "success",
                message = "Update cycle completed successfully",
                timestamp = System.currentTimeMillis(),
                executionTimeMs = result.executionTimeMs,
                data = UpdateData(
                    symbolsFetched = tradingProperties.getCurrenciesList().size,
                    positionsCount = positions.size,
                    positionsPlaced = result.positionsPlaced,
                    accountValueUsd = account.accountValue.toPlainString(),
                    promptSizeBytes = result.promptSize,
                    fileWritten = File(promptProperties.outputPath).exists()
                )
            )

            log.info { "Update completed in ${result.executionTimeMs}ms - Symbols: ${response.data?.symbolsFetched}, Positions: ${response.data?.positionsPlaced}" }
            ResponseEntity.ok(response)

        } catch (e: Exception) {
            log.error(e) { "Error during update" }

            val errorResponse = ErrorResponse(
                status = "error",
                message = e.message ?: "Unknown error occurred",
                timestamp = System.currentTimeMillis(),
                errorDetails = e.stackTraceToString().take(500),
                path = request.requestURI
            )
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
        }
    }

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<StatusResponse> {
        val now = Instant.now().toEpochMilli()
        val sessionStart = orchestrator.getSessionStartTime()
        val uptimeSeconds = (now - sessionStart) / 1000
        val minutesSinceStart = (now - sessionStart) / 60000

        val response = StatusResponse(
            status = "running",
            service = "AI Trading System",
            version = "2.0.0-clean-architecture",
            uptime = UptimeInfo(
                startTime = sessionStart,
                startTimeFormatted = dateFormatter.format(Instant.ofEpochMilli(sessionStart)),
                uptimeSeconds = uptimeSeconds,
                uptimeFormatted = formatDuration(uptimeSeconds)
            ),
            session = SessionInfo(
                invocationCount = orchestrator.getInvocationCount(),
                lastUpdateTime = orchestrator.getLastUpdateTime(),
                lastUpdateFormatted = orchestrator.getLastUpdateTime()?.let {
                    dateFormatter.format(Instant.ofEpochMilli(it))
                },
                minutesSinceStart = minutesSinceStart
            ),
            scheduler = SchedulerInfo(
                enabled = true,
                intervalMs = 180000,
                intervalFormatted = "3 minutes",
                nextExecutionEstimate = estimateNextExecution()
            ),
            trading = TradingInfo(
                autoExecuteEnabled = tradingProperties.autoExecute,
                symbolsCount = tradingProperties.getCurrenciesList().size,
                symbols = tradingProperties.getCurrenciesList()
            )
        )

        return ResponseEntity.ok(response)
    }

    @GetMapping("/account")
    fun getAccountInfo(): ResponseEntity<Map<String, Any>> = runBlocking {
        try {
            val account = orchestrator.getAccountInfo()
            val positions = orchestrator.getPositions()

            ResponseEntity.ok(mapOf(
                "accountValue" to account.accountValue.toPlainString(),
                "availableCash" to account.availableCash.toPlainString(),
                "totalReturn" to account.totalReturn.toPlainString(),
                "sharpeRatio" to (account.sharpeRatio?.toPlainString() ?: "N/A"),
                "positionsCount" to positions.size,
                "positions" to positions.map { pos ->
                    mapOf(
                        "symbol" to pos.symbol,
                        "quantity" to pos.quantity.toPlainString(),
                        "entryPrice" to pos.entryPrice.toPlainString(),
                        "currentPrice" to pos.currentPrice.toPlainString(),
                        "unrealizedPnl" to pos.unrealizedPnl.toPlainString(),
                        "leverage" to pos.leverage
                    )
                }
            ))
        } catch (e: Exception) {
            log.error(e) { "Failed to fetch account info" }
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf(
                "error" to (e.message ?: "Failed to fetch account info")
            ))
        }
    }

    @GetMapping("/stats")
    fun getSessionStats(): ResponseEntity<SessionStatsResponse> {
        val now = Instant.now().toEpochMilli()
        val sessionStart = orchestrator.getSessionStartTime()
        val minutesSinceStart = (now - sessionStart) / 60000

        val response = SessionStatsResponse(
            sessionStartTime = sessionStart,
            invocationCount = orchestrator.getInvocationCount(),
            minutesSinceStart = minutesSinceStart,
            currencies = tradingProperties.getCurrenciesList(),
            scheduledUpdateInterval = "180 seconds (3 minutes)",
            outputPath = promptProperties.outputPath,
            autoExecuteEnabled = tradingProperties.autoExecute
        )

        return ResponseEntity.ok(response)
    }

    @GetMapping("/prompt-info")
    fun getPromptInfo(): ResponseEntity<Map<String, Any>> {
        val promptFile = File(promptProperties.outputPath)

        return if (promptFile.exists()) {
            ResponseEntity.ok(mapOf(
                "exists" to true,
                "path" to promptProperties.outputPath,
                "sizeBytes" to promptFile.length(),
                "lastModified" to promptFile.lastModified(),
                "lastModifiedFormatted" to dateFormatter.format(
                    Instant.ofEpochMilli(promptFile.lastModified())
                ),
                "lineCount" to promptFile.useLines { it.count() },
                "readable" to promptFile.canRead()
            ))
        } else {
            ResponseEntity.ok(mapOf(
                "exists" to false,
                "path" to promptProperties.outputPath,
                "message" to "Prompt file not yet created"
            ))
        }
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error(e) { "Unhandled exception in controller" }

        val errorResponse = ErrorResponse(
            status = "error",
            message = e.message ?: "Internal server error",
            timestamp = System.currentTimeMillis(),
            errorDetails = e.stackTraceToString().take(500),
            path = request.requestURI
        )

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }

    private fun formatDuration(seconds: Long): String {
        val duration = Duration.ofSeconds(seconds)
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        val secs = duration.seconds % 60

        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            append("${secs}s")
        }.trim()
    }

    private fun estimateNextExecution(): String {
        val lastUpdate = orchestrator.getLastUpdateTime() ?: orchestrator.getSessionStartTime()
        val nextExecution = lastUpdate + 180000 // 3 minutes
        val now = System.currentTimeMillis()

        return if (nextExecution > now) {
            val secondsUntil = (nextExecution - now) / 1000
            "in ${formatDuration(secondsUntil)}"
        } else {
            "overdue (should trigger soon)"
        }
    }
}
package ru.driics.aitrade.controller

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.driics.aitrade.config.PromptProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.service.OkxMarketDataService
import ru.driics.aitrade.service.PromptSchedulerService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/prompt")
class PromptController(
    private val promptSchedulerService: PromptSchedulerService,
    private val okxMarketDataService: OkxMarketDataService,
    private val tradingProperties: TradingProperties,
    private val promptProperties: PromptProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("UTC"))

    @Volatile
    private var lastUpdateTime: Long? = null

    @GetMapping("/health")
    fun health(): ResponseEntity<HealthResponse> {
        val now = Instant.now().toEpochMilli()
        val sessionStart = okxMarketDataService.getSessionStartTime()
        val uptimeSeconds = (now - sessionStart) / 1000

        val response = HealthResponse(
            status = "UP",
            message = "AI Trader is running and healthy",
            timestamp = now,
            uptime = formatDuration(uptimeSeconds),
            lastUpdateTime = lastUpdateTime,
            invocationCount = okxMarketDataService.getInvocationCount(),
            sessionStartTime = sessionStart
        )

        log.debug("Health check requested - Status: UP, Invocations: ${response.invocationCount}")
        return ResponseEntity.ok(response)
    }

    @PostMapping("/update")
    fun updatePrompt(request: HttpServletRequest): ResponseEntity<Any> {
        log.info("Received manual prompt update request from ${request.remoteAddr}")
        val startTime = System.currentTimeMillis()

        return try {
            // Trigger update
            val result = promptSchedulerService.triggerPromptUpdate()
            val executionTime = System.currentTimeMillis() - startTime
            lastUpdateTime = System.currentTimeMillis()

            if (result.startsWith("Error")) {
                // Update failed
                val errorResponse = ErrorResponse(
                    status = "error",
                    message = result,
                    timestamp = lastUpdateTime!!,
                    errorDetails = "Update execution failed. Check logs for details.",
                    path = request.requestURI
                )
                log.warn("Prompt update failed in ${executionTime}ms: $result")
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
            }

            // Fetch current data for response
            val currencies = tradingProperties.getCurrenciesList()
            val positions = okxMarketDataService.fetchPositions()
            val accountInfo = okxMarketDataService.fetchAccountInfo()

            // Calculate prompt size (approximate from file if available)
            val promptFile = java.io.File(promptProperties.outputPath)
            val promptSize = if (promptFile.exists()) promptFile.length().toInt() else 0

            val response = UpdateResponse(
                status = "success",
                message = "Prompt updated successfully",
                timestamp = lastUpdateTime!!,
                executionTimeMs = executionTime,
                data = UpdateData(
                    symbolsFetched = currencies.size,
                    positionsCount = positions.size,
                    accountValueUsd = accountInfo.accountValue.toPlainString(),
                    promptSizeBytes = promptSize,
                    fileWritten = promptFile.exists()
                )
            )

            log.info("Prompt updated successfully in ${executionTime}ms - " +
                    "Symbols: ${currencies.size}, Positions: ${positions.size}")
            ResponseEntity.ok(response)

        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            log.error("Error updating prompt after ${executionTime}ms", e)

            val errorResponse = ErrorResponse(
                status = "error",
                message = e.message ?: "Unknown error occurred",
                timestamp = System.currentTimeMillis(),
                errorDetails = e.stackTraceToString().take(500), // First 500 chars
                path = request.requestURI
            )
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
        }
    }

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<StatusResponse> {
        val now = Instant.now().toEpochMilli()
        val sessionStart = okxMarketDataService.getSessionStartTime()
        val uptimeSeconds = (now - sessionStart) / 1000
        val minutesSinceStart = (now - sessionStart) / 60000

        val response = StatusResponse(
            status = "running",
            service = "AI Trader Prompt Builder",
            version = "1.0.0",
            uptime = UptimeInfo(
                startTime = sessionStart,
                startTimeFormatted = dateFormatter.format(Instant.ofEpochMilli(sessionStart)),
                uptimeSeconds = uptimeSeconds,
                uptimeFormatted = formatDuration(uptimeSeconds)
            ),
            session = SessionInfo(
                invocationCount = okxMarketDataService.getInvocationCount(),
                lastUpdateTime = lastUpdateTime,
                lastUpdateFormatted = lastUpdateTime?.let {
                    dateFormatter.format(Instant.ofEpochMilli(it))
                },
                minutesSinceStart = minutesSinceStart
            ),
            scheduler = SchedulerInfo(
                enabled = true,
                intervalMs = 180000, // 3 minutes
                intervalFormatted = "3 minutes",
                nextExecutionEstimate = estimateNextExecution()
            )
        )

        return ResponseEntity.ok(response)
    }

    @GetMapping("/stats")
    fun getSessionStats(): ResponseEntity<SessionStatsResponse> {
        val now = Instant.now().toEpochMilli()
        val sessionStart = okxMarketDataService.getSessionStartTime()
        val minutesSinceStart = (now - sessionStart) / 60000

        val response = SessionStatsResponse(
            sessionStartTime = sessionStart,
            invocationCount = okxMarketDataService.getInvocationCount(),
            minutesSinceStart = minutesSinceStart,
            currencies = tradingProperties.getCurrenciesList(),
            scheduledUpdateInterval = "180 seconds (3 minutes)",
            outputPath = promptProperties.outputPath
        )

        return ResponseEntity.ok(response)
    }

    @GetMapping("/prompt-info")
    fun getPromptInfo(): ResponseEntity<Map<String, Any>> {
        val promptFile = java.io.File(promptProperties.outputPath)

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
        log.error("Unhandled exception in controller", e)

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
        val lastUpdate = lastUpdateTime ?: okxMarketDataService.getSessionStartTime()
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
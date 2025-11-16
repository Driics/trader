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
import ru.driics.aitrade.controller.mapper.TradingSystemResponseMapper
import ru.driics.aitrade.controller.util.TradingSystemUtils
import java.time.Clock
import java.time.Instant

/**
 * REST controller for trading system monitoring and control.
 * Follows Clean Architecture: depends only on orchestrator and configuration.
 */
@RestController
@RequestMapping("/api/trading")
class TradingSystemController(
    private val orchestrator: UpdateCycleOrchestrator,
    private val tradingProperties: TradingProperties,
    private val promptProperties: PromptProperties,
    private val clock: Clock,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<HealthResponse> {
        val now = clock.millis()
        val sessionStart = orchestrator.getSessionStartTime()
        val uptimeSeconds = (now - sessionStart) / 1000

        val response = TradingSystemResponseMapper.mapToHealthResponse(
            orchestrator,
            uptimeSeconds
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
                val errorResponse = TradingSystemResponseMapper.mapToErrorResponse(
                    message = result.message,
                    errorDetails = "Update execution failed. Check logs for details.",
                    path = request.requestURI,
                    clock = clock
                )
                log.warn { "Update failed in ${result.executionTimeMs}ms: ${result.message}" }
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
            }

            // Fetch current data for response
            val account = runBlocking { orchestrator.getAccountInfo() }
            val positions = runBlocking { orchestrator.getPositions() }

            val response = TradingSystemResponseMapper.mapToUpdateResponse(
                result = result,
                account = account,
                positions = positions,
                tradingProperties = tradingProperties,
                promptProperties = promptProperties,
                clock = clock
            )

            log.info { "Update completed in ${result.executionTimeMs}ms - Symbols: ${response.data?.symbolsFetched}, Positions: ${response.data?.positionsPlaced}" }
            ResponseEntity.ok(response)

        } catch (e: Exception) {
            log.error(e) { "Error during update" }

            val errorResponse = TradingSystemResponseMapper.mapToErrorResponse(
                message = e.message ?: "Unknown error occurred",
                errorDetails = e.stackTraceToString().take(500),
                path = request.requestURI,
                clock = clock
            )
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
        }
    }

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<StatusResponse> {
        val nextExecutionEstimate = TradingSystemUtils.estimateNextExecution(orchestrator, clock)
        val response = TradingSystemResponseMapper.mapToStatusResponse(
            orchestrator = orchestrator,
            tradingProperties = tradingProperties,
            nextExecutionEstimate = nextExecutionEstimate
        )
        return ResponseEntity.ok(response)
    }

    @GetMapping("/account")
    fun getAccountInfo(): ResponseEntity<Map<String, Any>> = runBlocking {
        try {
            val account = orchestrator.getAccountInfo()
            val positions = orchestrator.getPositions()
            val response = TradingSystemResponseMapper.mapToAccountResponse(account, positions)
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            log.error(e) { "Failed to fetch account info" }
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf(
                "error" to (e.message ?: "Failed to fetch account info")
            ))
        }
    }

    @GetMapping("/stats")
    fun getSessionStats(): ResponseEntity<SessionStatsResponse> {
        val response = TradingSystemResponseMapper.mapToSessionStatsResponse(
            orchestrator = orchestrator,
            tradingProperties = tradingProperties,
            promptProperties = promptProperties
        )
        return ResponseEntity.ok(response)
    }

    @GetMapping("/prompt-info")
    fun getPromptInfo(): ResponseEntity<Map<String, Any>> {
        val response = TradingSystemResponseMapper.mapToPromptInfoResponse(promptProperties)
        return ResponseEntity.ok(response)
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error(e) { "Unhandled exception in controller" }

        val errorResponse = TradingSystemResponseMapper.mapToErrorResponse(
            message = e.message ?: "Internal server error",
            errorDetails = e.stackTraceToString().take(500),
            path = request.requestURI,
            clock = clock
        )

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }
}
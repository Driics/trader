package ru.driics.aitrade.controller.mapper

import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.application.orchestrator.UpdateCycleResult
import ru.driics.aitrade.config.PromptProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.controller.*
import ru.driics.aitrade.controller.util.TradingSystemUtils
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.Position
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Maps domain models and orchestrator data to HTTP response DTOs.
 */
object TradingSystemResponseMapper {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("UTC"))

    fun mapToHealthResponse(
        orchestrator: UpdateCycleOrchestrator,
        uptimeSeconds: Long
    ): HealthResponse {
        val now = Instant.now().toEpochMilli()
        val sessionStart = orchestrator.getSessionStartTime()

        return HealthResponse(
            status = "UP",
            message = "AI Trading System is running and healthy",
            timestamp = now,
            uptime = TradingSystemUtils.formatDuration(uptimeSeconds),
            lastUpdateTime = orchestrator.getLastUpdateTime(),
            invocationCount = orchestrator.getInvocationCount(),
            sessionStartTime = sessionStart
        )
    }

    fun mapToUpdateResponse(
        result: UpdateCycleResult,
        account: AccountInfo,
        positions: List<Position>,
        tradingProperties: TradingProperties,
        promptProperties: PromptProperties,
        clock: Clock
    ): UpdateResponse {
        return UpdateResponse(
            status = "success",
            message = "Update cycle completed successfully",
            timestamp = clock.instant().toEpochMilli(),
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
    }

    fun mapToErrorResponse(
        message: String,
        errorDetails: String?,
        path: String,
        clock: Clock
    ): ErrorResponse {
        return ErrorResponse(
            status = "error",
            message = message,
            timestamp = clock.instant().toEpochMilli(),
            errorDetails = errorDetails,
            path = path
        )
    }

    fun mapToStatusResponse(
        orchestrator: UpdateCycleOrchestrator,
        tradingProperties: TradingProperties,
        nextExecutionEstimate: String
    ): StatusResponse {
        val now = Instant.now().toEpochMilli()
        val sessionStart = orchestrator.getSessionStartTime()
        val uptimeSeconds = (now - sessionStart) / 1000
        val minutesSinceStart = (now - sessionStart) / 60000

        return StatusResponse(
            status = "running",
            service = "AI Trading System",
            version = "2.0.0-clean-architecture",
            uptime = UptimeInfo(
                startTime = sessionStart,
                startTimeFormatted = dateFormatter.format(Instant.ofEpochMilli(sessionStart)),
                uptimeSeconds = uptimeSeconds,
                uptimeFormatted = TradingSystemUtils.formatDuration(uptimeSeconds)
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
                nextExecutionEstimate = nextExecutionEstimate
            ),
            trading = TradingInfo(
                autoExecuteEnabled = tradingProperties.autoExecute,
                symbolsCount = tradingProperties.getCurrenciesList().size,
                symbols = tradingProperties.getCurrenciesList()
            )
        )
    }

    fun mapToAccountResponse(account: AccountInfo, positions: List<Position>): Map<String, Any> {
        return mapOf(
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
        )
    }

    fun mapToSessionStatsResponse(
        orchestrator: UpdateCycleOrchestrator,
        tradingProperties: TradingProperties,
        promptProperties: PromptProperties
    ): SessionStatsResponse {
        val now = Instant.now().toEpochMilli()
        val sessionStart = orchestrator.getSessionStartTime()
        val minutesSinceStart = (now - sessionStart) / 60000

        return SessionStatsResponse(
            sessionStartTime = sessionStart,
            invocationCount = orchestrator.getInvocationCount(),
            minutesSinceStart = minutesSinceStart,
            currencies = tradingProperties.getCurrenciesList(),
            scheduledUpdateInterval = "180 seconds (3 minutes)",
            outputPath = promptProperties.outputPath,
            autoExecuteEnabled = tradingProperties.autoExecute
        )
    }

    fun mapToPromptInfoResponse(promptProperties: PromptProperties): Map<String, Any> {
        val promptFile = File(promptProperties.outputPath)

        return if (promptFile.exists()) {
            mapOf(
                "exists" to true,
                "path" to promptProperties.outputPath,
                "sizeBytes" to promptFile.length(),
                "lastModified" to promptFile.lastModified(),
                "lastModifiedFormatted" to dateFormatter.format(
                    Instant.ofEpochMilli(promptFile.lastModified())
                ),
                "lineCount" to promptFile.useLines { it.count() },
                "readable" to promptFile.canRead()
            )
        } else {
            mapOf(
                "exists" to false,
                "path" to promptProperties.outputPath,
                "message" to "Prompt file not yet created"
            )
        }
    }
}


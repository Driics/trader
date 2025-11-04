package ru.driics.aitrade.controller

data class HealthResponse(
    val status: String,
    val message: String,
    val timestamp: Long,
    val uptime: String,
    val lastUpdateTime: Long?,
    val invocationCount: Long,
    val sessionStartTime: Long
)

data class UpdateResponse(
    val status: String,
    val message: String,
    val timestamp: Long,
    val executionTimeMs: Long,
    val data: UpdateData?
)

data class UpdateData(
    val symbolsFetched: Int,
    val positionsCount: Int,
    val positionsPlaced: Int,
    val accountValueUsd: String,
    val promptSizeBytes: Int,
    val fileWritten: Boolean
)

data class StatusResponse(
    val status: String,
    val service: String,
    val version: String,
    val uptime: UptimeInfo,
    val session: SessionInfo,
    val scheduler: SchedulerInfo,
    val trading: TradingInfo
)

data class UptimeInfo(
    val startTime: Long,
    val startTimeFormatted: String,
    val uptimeSeconds: Long,
    val uptimeFormatted: String
)

data class SessionInfo(
    val invocationCount: Long,
    val lastUpdateTime: Long?,
    val lastUpdateFormatted: String?,
    val minutesSinceStart: Long
)

data class SchedulerInfo(
    val enabled: Boolean,
    val intervalMs: Long,
    val intervalFormatted: String,
    val nextExecutionEstimate: String
)

data class TradingInfo(
    val autoExecuteEnabled: Boolean,
    val symbolsCount: Int,
    val symbols: List<String>
)

data class ErrorResponse(
    val status: String,
    val message: String,
    val timestamp: Long,
    val errorDetails: String?,
    val path: String
)

data class SessionStatsResponse(
    val sessionStartTime: Long,
    val invocationCount: Long,
    val minutesSinceStart: Long,
    val currencies: List<String>,
    val scheduledUpdateInterval: String,
    val outputPath: String,
    val autoExecuteEnabled: Boolean
)
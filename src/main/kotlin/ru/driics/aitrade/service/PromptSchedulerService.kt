package ru.driics.aitrade.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.application.orchestrator.UpdateCycleResult

/**
 * Scheduler service for periodic trading system updates.
 * Acts as an entry point for both Scheduled and Manual triggers.
 */
@Service
class PromptSchedulerService(
    private val orchestrator: UpdateCycleOrchestrator
) {
    private companion object {
        val log = KotlinLogging.logger {}
    }

    /**
     * Executes scheduled update.
     * Interval configurable via 'ai.trade.scheduler.interval-ms', defaults to 15 minutes.
     *
     * Note: @Scheduled methods must be blocking/void in Spring, so runBlocking is necessary here,
     * but it only blocks the single scheduler thread, not the web server threads.
     */
    @Scheduled(
        fixedDelayString = "\${ai.trade.scheduler.interval-ms:900000}",
        initialDelayString = "\${ai.trade.scheduler.initial-delay-ms:5000}"
    )
    fun scheduledUpdate() {
        runBlocking {
            log.info { "Clock tick: Triggering scheduled update..." }

            runCatching {
                orchestrator.runOnce()
            }.onSuccess { result ->
                logSuccess(result)
            }.onFailure { e ->
                log.error(e) { "Scheduled update failed with unexpected exception" }
            }
        }
    }

    /**
     * Triggers manual update (called from REST API).
     * Marked as 'suspend' so the Controller does not need to block a thread waiting for the result.
     */
    suspend fun triggerManualUpdate(): UpdateCycleResult {
        log.info { "Manual update triggered via API" }
        return orchestrator.runOnce()
    }

    private fun logSuccess(result: UpdateCycleResult) =
        if (result.success) {
            log.info { "Update finished. Positions placed: ${result.positionsPlaced}" }
        } else {
            log.warn { "Update finished with warning: ${result.message}" }
        }
}
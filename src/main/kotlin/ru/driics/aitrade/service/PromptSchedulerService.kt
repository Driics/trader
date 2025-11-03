// src/main/kotlin/ru/driics/aitrade/service/PromptSchedulerService.kt
package ru.driics.aitrade.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator

/**
 * Scheduler service for periodic trading system updates.
 * Minimal responsibility: scheduling only. Business logic is in UpdateCycleOrchestrator.
 */
@Service
class PromptSchedulerService(
    private val orchestrator: UpdateCycleOrchestrator
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Executes scheduled update every 3 minutes.
     * Initial delay: 5 seconds after startup.
     */
    @Scheduled(fixedDelay = 180000, initialDelay = 5000)
    fun updatePrompt() {
        log.info { "╔══════════════════════════════════════════════════════" }
        log.info { "║ Scheduled Update Triggered" }
        log.info { "╚══════════════════════════════════════════════════════" }

        try {
            val result = runBlocking { orchestrator.runOnce() }

            if (result.success) {
                log.info { "✓ Scheduled update completed successfully in ${result.executionTimeMs}ms" }
                if (result.positionsPlaced > 0) {
                    log.info { "  → ${result.positionsPlaced} position(s) placed" }
                }
            } else {
                log.error { "✗ Scheduled update failed: ${result.message}" }
            }
        } catch (e: Exception) {
            log.error(e) { "Unexpected error during scheduled update" }
        }

        log.info { "═══════════════════════════════════════════════════════\n" }
    }

    /**
     * Triggers manual update (called from REST API).
     * Returns execution result for API response.
     */
    fun triggerManualUpdate(): ru.driics.aitrade.application.orchestrator.UpdateCycleResult {
        log.info { "Manual update triggered via API" }
        return runBlocking { orchestrator.runOnce() }
    }
}
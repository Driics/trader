package ru.driics.aitrade.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.application.orchestrator.UpdateCycleResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    // P1: run the cycle on a dedicated single-thread dispatcher instead of blocking the Spring
    // scheduler thread (whose pool is shared with every other @Scheduled task in the app).
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "prompt-scheduler").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // P1: offloading to our own dispatcher forfeits @Scheduled(fixedDelay)'s non-overlap guarantee
    // (the method now returns immediately), so this single-flight guard is mandatory: if a cycle is
    // still running when the next tick fires, that tick is skipped rather than overlapping.
    private val inFlight = AtomicBoolean(false)

    /**
     * Executes scheduled update.
     * Interval configurable via 'ai.trade.scheduler.interval-ms', defaults to 15 minutes.
     *
     * Returns immediately; the cycle runs on [scope]. Overlapping ticks are skipped (single-flight).
     */
    @Scheduled(
        fixedDelayString = "\${ai.trade.scheduler.interval-ms:900000}",
        initialDelayString = "\${ai.trade.scheduler.initial-delay-ms:5000}"
    )
    fun scheduledUpdate() {
        if (!inFlight.compareAndSet(false, true)) {
            log.warn { "Previous update still running; skipping this tick (single-flight)" }
            return
        }

        scope.launch {
            try {
                log.info { "Clock tick: Triggering scheduled update..." }
                runCatching {
                    orchestrator.runOnce()
                }.onSuccess { result ->
                    logSuccess(result)
                }.onFailure { e ->
                    log.error(e) { "Scheduled update failed with unexpected exception" }
                }
            } finally {
                inFlight.set(false)
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

    @PreDestroy
    fun shutdown() {
        log.info { "Shutting down prompt scheduler dispatcher" }
        scope.cancel()
        dispatcher.close()
    }

    private fun logSuccess(result: UpdateCycleResult) =
        if (result.success) {
            log.info { "Update finished. Positions placed: ${result.positionsPlaced}" }
        } else {
            log.warn { "Update finished with warning: ${result.message}" }
        }
}

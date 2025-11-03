package ru.driics.aitrade.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.driics.aitrade.application.orchestrator.UpdateCycleOrchestrator
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.model.AIAction
import ru.driics.aitrade.model.MarketState
import java.time.Instant

@Service
class PromptSchedulerService(
    private val orchestrator: UpdateCycleOrchestrator,
    private val tradingProperties: TradingProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 180000, initialDelay = 5000)
    fun updatePrompt() {
        log.info("=== Starting scheduled prompt update ===")
        val result = runBlocking { orchestrator.runOnce() }
        if (result.startsWith("Error")) {
            log.error("Scheduled prompt update failed: $result")
        } else {
            log.info("=== Prompt update completed successfully ===")
        }
    }

    fun triggerPromptUpdate(): String = runBlocking { orchestrator.runOnce() }

}
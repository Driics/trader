package ru.driics.aitrade.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.model.MarketState
import java.time.Instant

@Service
class PromptSchedulerService(
    private val okxMarketDataService: OkxMarketDataService,
    private val promptBuilderService: PromptBuilderService,
    private val tradingProperties: TradingProperties,
    private val koogAiService: KoogAiService,
    private val aiTradeExecutionService: AiTradeExecutionService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 180000, initialDelay = 5000)
    fun updatePrompt() {
        log.info("=== Starting scheduled prompt update ===")
        val result = executePromptUpdate()
        if (result.startsWith("Error")) {
            log.error("Scheduled prompt update failed: $result")
        } else {
            log.info("=== Prompt update completed successfully ===")
        }
    }

    fun triggerPromptUpdate(): String {
        log.info("Manual prompt update triggered")
        return executePromptUpdate()
    }

    private fun executePromptUpdate(): String {
        return try {
            val currencies = tradingProperties.getCurrenciesList()

            if (currencies.isEmpty()) {
                return "Error: No currencies configured for trading"
            }

            val marketData = okxMarketDataService.fetchMarketData(currencies)
            if (marketData.isEmpty()) {
                log.warn("No market data fetched, but continuing...")
            }

            val accountInfo = okxMarketDataService.fetchAccountInfo()
            val positions = okxMarketDataService.fetchPositions()

            val marketState = MarketState(
                timestamp = Instant.now().toEpochMilli(),
                minutesSinceStart = (Instant.now().toEpochMilli() - okxMarketDataService.getSessionStartTime()) / 60000,
                invocationCount = okxMarketDataService.getInvocationCount(),
                currencies = marketData,
                account = accountInfo,
                positions = positions
            )

            val prompt = promptBuilderService.buildPrompt(
                marketState,
                okxMarketDataService.getSessionStartTime(),
                okxMarketDataService.getInvocationCount()
            )

            val writeSuccess = promptBuilderService.writePromptToFile(prompt)
            if (!writeSuccess) {
                log.warn("Failed to write prompt to file, but continuing...")
            }

            promptBuilderService.printPromptToConsole(prompt)

            // ✅ AI Analysis with Koog Spring Boot Starter
            log.info("Starting Koog AI analysis (auto-configured)...")
            runBlocking {
                val ai = koogAiService.analyzePrompt(prompt)
                if (ai.isSuccess) {
                    log.info(
                        "AI ({} / {}) analysis complete in {} ms. Preview:\n {}",
                        ai.provider, ai.model, ai.executionTimeMs, ai.response
                    )

                    if (tradingProperties.autoExecute) {
                        try {
                            val execResults = aiTradeExecutionService.execute(ai.response)
                            val placed = execResults.count { it.action == "placed" }
                            val skipped = execResults.size - placed
                            log.info(
                                "Auto-execution done: placed={}, skipped={}. Details:\n{}",
                                placed, skipped,
                                execResults.joinToString("\n") {
                                    "- ${it.symbol}: ${it.action} (${it.message})" +
                                            (it.ordId?.let { id -> ", ordId=$id" } ?: "")
                                }
                            )
                        } catch (e: Exception) {
                            log.error("Auto-execution failed; continuing scheduler cycle", e)
                        }
                    } else {
                        log.info("Auto-execution disabled (trading.auto-execute=false). Skipping order placement.")
                    }
                } else {
                    log.warn(
                        "AI ({} / {}) analysis failed in {} ms: {}",
                        ai.provider, ai.model, ai.executionTimeMs, ai.errorMessage
                    )
                }
            }

            "Prompt updated and analyzed successfully"
        } catch (e: Exception) {
            log.error("Error during prompt update", e)
            "Error: ${e.message}"
        }
    }
}
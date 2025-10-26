package ru.driics.aitrade.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.model.MarketState
import java.time.Instant

@Service
class PromptSchedulerService(
    private val okxApiService: OkxApiService,
    private val promptBuilderService: PromptBuilderService,
    private val tradingProperties: TradingProperties
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

            // Validate currencies list
            if (currencies.isEmpty()) {
                return "Error: No currencies configured for trading"
            }

            // Fetch market data with partial failure tolerance
            val marketData = okxApiService.fetchMarketData(currencies)
            if (marketData.isEmpty()) {
                log.warn("No market data fetched, but continuing...")
            }

            // Fetch account info and positions
            val accountInfo = okxApiService.fetchAccountInfo()
            val positions = okxApiService.fetchPositions()

            // Build market state
            val marketState = MarketState(
                timestamp = Instant.now().toEpochMilli(),
                minutesSinceStart = (Instant.now().toEpochMilli() - okxApiService.getSessionStartTime()) / 60000,
                invocationCount = okxApiService.getInvocationCount(),
                currencies = marketData,
                account = accountInfo,
                positions = positions
            )

            // Build and output prompt
            val prompt = promptBuilderService.buildPrompt(
                marketState,
                okxApiService.getSessionStartTime(),
                okxApiService.getInvocationCount()
            )

            // Write to file and console
            val writeSuccess = promptBuilderService.writePromptToFile(prompt)
            if (!writeSuccess) {
                log.warn("Failed to write prompt to file, but continuing...")
            }

            promptBuilderService.printPromptToConsole(prompt)

            "Prompt updated successfully"
        } catch (e: Exception) {
            log.error("Error during prompt update", e)
            "Error: ${e.message}"
        }
    }
}
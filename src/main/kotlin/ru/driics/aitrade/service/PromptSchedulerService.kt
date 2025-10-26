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

    /**
     * Executes every 3 minutes to fetch market data and generate prompt
     */
    @Scheduled(fixedDelay = 180000, initialDelay = 5000) // 3 minutes = 180000 ms
    fun updatePrompt() {
        log.info("=== Starting scheduled prompt update ===")
        try {
            val currencies = tradingProperties.getCurrenciesList()
            
            // Fetch market data
            val marketData = okxApiService.fetchMarketData(currencies)
            
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
            promptBuilderService.writePromptToFile(prompt)
            promptBuilderService.printPromptToConsole(prompt)
            
            log.info("=== Prompt update completed successfully ===")
        } catch (e: Exception) {
            log.error("Error during scheduled prompt update", e)
        }
    }

    /**
     * Manual trigger via method call (can be exposed via REST endpoint if needed)
     */
    fun triggerPromptUpdate(): String {
        log.info("Manual prompt update triggered")
        try {
            val currencies = tradingProperties.getCurrenciesList()
            
            // Fetch market data
            val marketData = okxApiService.fetchMarketData(currencies)
            
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
            promptBuilderService.writePromptToFile(prompt)
            promptBuilderService.printPromptToConsole(prompt)
            
            return "Prompt updated successfully"
        } catch (e: Exception) {
            log.error("Error during manual prompt update", e)
            return "Error: ${e.message}"
        }
    }
}
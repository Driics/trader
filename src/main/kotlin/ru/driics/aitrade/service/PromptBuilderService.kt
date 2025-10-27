package ru.driics.aitrade.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.PromptProperties
import ru.driics.aitrade.model.MarketState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Service
class PromptBuilderService(
    private val promptProperties: PromptProperties,
    private val promptFormatterService: PromptFormatterService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun buildPrompt(
        marketState: MarketState,
        sessionStartTime: Long,
        invocationCount: Long
    ): String {
        val minutesSinceStart = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - sessionStartTime)
        val currentTime = LocalDateTime.now(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))

        return buildString {
            appendHeader(minutesSinceStart, currentTime, invocationCount)
            appendMarketData(marketState)
            appendAccountInfo(marketState)
        }
    }

    fun writePromptToFile(prompt: String): Boolean {
        return try {
            val target: Path = Path.of(promptProperties.outputPath)
            target.parent?.let { Files.createDirectories(it) }

            val tmp: Path = target.resolveSibling(target.fileName.toString() + ".tmp")
            Files.writeString(tmp, prompt, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }

            log.info("Prompt written to ${promptProperties.outputPath}")
            true
        } catch (e: Exception) {
            log.error("Error writing prompt to file", e)
            false
        }
    }

    fun printPromptToConsole(prompt: String) {
        println(prompt)
    }

    private fun StringBuilder.appendHeader(minutes: Long, time: String, count: Long) {
        append("It has been $minutes minutes since you started trading. ")
        append("The current time is $time and you've been invoked $count times. ")
        append("Below, we are providing you with a variety of state data, price data, and predictive signals so you can discover alpha. ")
        append("Below that is your current account information, value, performance, positions, etc. ")
        append("ALL OF THE PRICE OR SIGNAL DATA BELOW IS ORDERED: OLDEST → NEWEST\n")
        append("Timeframes note: Unless stated otherwise in a section title, intraday series are provided at 3‑minute intervals. ")
        append("If a coin uses a different interval, it is explicitly stated in that coin's section.\n\n")
    }

    private fun StringBuilder.appendMarketData(marketState: MarketState) {
        append("CURRENT MARKET STATE FOR ALL COINS\n")

        for ((symbol, data) in marketState.currencies) {
            append("ALL $symbol DATA\n")
            append("current_price = ${promptFormatterService.formatNumber(data.currentPrice)}, ")
            append("current_ema20 = ${promptFormatterService.formatNumber(data.currentEma20)}, ")
            append("current_macd = ${promptFormatterService.formatNumber(data.currentMacd)}, ")
            append("current_rsi (7 period) = ${promptFormatterService.formatNumber(data.currentRsi7)}\n")

            // Open Interest and Funding Rate
            if (data.openInterest != null || data.fundingRate != null) {
                append("In addition, here is the latest $symbol open interest and funding rate for perps (the instrument you are trading):\n")
                data.openInterest?.let {
                    append("Open Interest: Latest: ${promptFormatterService.formatNumber(it)} Average: ${promptFormatterService.formatNumber(it)}\n")
                }
                data.fundingRate?.let {
                    append("Funding Rate: ${promptFormatterService.formatScientific(it)}\n")
                }
            }

            // Intraday series
            if (data.intradayPrices.isNotEmpty()) {
                append("Intraday series (3‑minute intervals, oldest → latest):\n")
                append("Mid prices: ${promptFormatterService.formatNumberList(data.intradayPrices)}\n")

                if (data.intradayEma20.isNotEmpty()) {
                    append("EMA indicators (20‑period): ${promptFormatterService.formatNumberList(data.intradayEma20)}\n")
                }
                if (data.intradayMacd.isNotEmpty()) {
                    append("MACD indicators: ${promptFormatterService.formatNumberList(data.intradayMacd)}\n")
                }
                if (data.intradayRsi7.isNotEmpty()) {
                    append("RSI indicators (7‑Period): ${promptFormatterService.formatNumberList(data.intradayRsi7)}\n")
                }
                if (data.intradayRsi14.isNotEmpty()) {
                    append("RSI indicators (14‑Period): ${promptFormatterService.formatNumberList(data.intradayRsi14)}\n")
                }
            }

            // 4-hour context
            if (data.ema20_4h != null || data.ema50_4h != null) {
                append("Longer‑term context (4‑hour timeframe):\n")

                if (data.ema20_4h != null && data.ema50_4h != null) {
                    append("20‑Period EMA: ${promptFormatterService.formatNumber(data.ema20_4h)} vs. 50‑Period EMA: ${promptFormatterService.formatNumber(data.ema50_4h)}\n")
                }

                if (data.atr3_4h != null && data.atr14_4h != null) {
                    append("3‑Period ATR: ${promptFormatterService.formatNumber(data.atr3_4h)} vs. 14‑Period ATR: ${promptFormatterService.formatNumber(data.atr14_4h)}\n")
                }

                if (data.volume4h != null && data.avgVolume4h != null) {
                    append("Current Volume: ${promptFormatterService.formatNumber(data.volume4h)} vs. Average Volume: ${promptFormatterService.formatNumber(data.avgVolume4h)}\n")
                }

                if (data.macd4h.isNotEmpty()) {
                    append("MACD indicators: ${promptFormatterService.formatNumberList(data.macd4h)}\n")
                }

                if (data.rsi14_4h.isNotEmpty()) {
                    append("RSI indicators (14‑Period): ${promptFormatterService.formatNumberList(data.rsi14_4h)}\n")
                }
            }

            append("\n")
        }
    }

    private fun StringBuilder.appendAccountInfo(marketState: MarketState) {
        append("HERE IS YOUR ACCOUNT INFORMATION & PERFORMANCE\n")
        append("Current Total Return (percent): ${promptFormatterService.formatPercent(marketState.account.totalReturn)}\n")
        append("Available Cash: ${promptFormatterService.formatNumber(marketState.account.availableCash)}\n")
        append("Current Account Value: ${promptFormatterService.formatNumber(marketState.account.accountValue)}\n")

        if (marketState.positions.isNotEmpty()) {
            append("Current live positions & performance: ")
            append(promptFormatterService.formatPositions(marketState.positions))
            append("\n")
        }

        marketState.account.sharpeRatio?.let {
            append("Sharpe Ratio: ${promptFormatterService.formatNumber(it)}\n")
        }
    }
}
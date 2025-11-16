package ru.driics.aitrade.domain.services

import ru.driics.aitrade.domain.model.MarketState
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Pure domain service for building trading prompts.
 * No infrastructure dependencies.
 * 
 * Note: For template-based prompts, use PromptTemplateService in application layer.
 * This builder is kept for backward compatibility and direct prompt construction.
 */
object PromptBuilder {

    fun build(
        marketState: MarketState,
        sessionStartTime: Long,
        invocationCount: Long
    ): String {
        val minutesSinceStart = TimeUnit.MILLISECONDS.toMinutes(
            System.currentTimeMillis() - sessionStartTime
        )
        val currentTime = LocalDateTime.now(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))

        return buildString {
            appendHeader(minutesSinceStart, currentTime, invocationCount)
            appendMarketData(marketState)
            appendAccountInfo(marketState)
        }
    }

    /**
     * Builds market data section as string (for template rendering).
     */
    fun buildMarketDataSection(marketState: MarketState): String {
        return buildString {
            appendMarketData(marketState)
        }
    }

    /**
     * Builds account info section as string (for template rendering).
     */
    fun buildAccountInfoSection(marketState: MarketState): String {
        return buildString {
            appendAccountInfo(marketState)
        }
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
            append("current_price = ${PromptFormatter.formatNumber(data.currentPrice)}, ")
            append("current_ema20 = ${PromptFormatter.formatNumber(data.currentEma20)}, ")
            append("current_macd = ${PromptFormatter.formatNumber(data.currentMacd)}, ")
            append("current_rsi (7 period) = ${PromptFormatter.formatNumber(data.currentRsi7)}\n")

            if (data.openInterest != null || data.fundingRate != null) {
                append("In addition, here is the latest $symbol open interest and funding rate for perps (the instrument you are trading):\n")
                data.openInterest?.let {
                    append("Open Interest: Latest: ${PromptFormatter.formatNumber(it)} Average: ${PromptFormatter.formatNumber(it)}\n")
                }
                data.fundingRate?.let {
                    append("Funding Rate: ${PromptFormatter.formatScientific(it)}\n")
                }
            }

            if (data.intradayPrices.isNotEmpty()) {
                append("Intraday series (3‑minute intervals, oldest → latest):\n")
                append("Mid prices: ${PromptFormatter.formatNumberList(data.intradayPrices)}\n")

                if (data.intradayEma20.isNotEmpty()) {
                    append("EMA indicators (20‑period): ${PromptFormatter.formatNumberList(data.intradayEma20)}\n")
                }
                if (data.intradayMacd.isNotEmpty()) {
                    append("MACD indicators: ${PromptFormatter.formatNumberList(data.intradayMacd)}\n")
                }
                if (data.intradayRsi7.isNotEmpty()) {
                    append("RSI indicators (7‑Period): ${PromptFormatter.formatNumberList(data.intradayRsi7)}\n")
                }
                if (data.intradayRsi14.isNotEmpty()) {
                    append("RSI indicators (14‑Period): ${PromptFormatter.formatNumberList(data.intradayRsi14)}\n")
                }
            }

            if (data.ema20_4h != null || data.ema50_4h != null) {
                append("Longer‑term context (4‑hour timeframe):\n")

                if (data.ema20_4h != null && data.ema50_4h != null) {
                    append("20‑Period EMA: ${PromptFormatter.formatNumber(data.ema20_4h)} vs. 50‑Period EMA: ${PromptFormatter.formatNumber(data.ema50_4h)}\n")
                }

                if (data.atr3_4h != null && data.atr14_4h != null) {
                    append("3‑Period ATR: ${PromptFormatter.formatNumber(data.atr3_4h)} vs. 14‑Period ATR: ${PromptFormatter.formatNumber(data.atr14_4h)}\n")
                }

                if (data.volume4h != null && data.avgVolume4h != null) {
                    append("Current Volume: ${PromptFormatter.formatNumber(data.volume4h)} vs. Average Volume: ${PromptFormatter.formatNumber(data.avgVolume4h)}\n")
                }

                if (data.macd4h.isNotEmpty()) {
                    append("MACD indicators: ${PromptFormatter.formatNumberList(data.macd4h)}\n")
                }

                if (data.rsi14_4h.isNotEmpty()) {
                    append("RSI indicators (14‑Period): ${PromptFormatter.formatNumberList(data.rsi14_4h)}\n")
                }
            }

            append("\n")
        }
    }

    private fun StringBuilder.appendAccountInfo(marketState: MarketState) {
        append("HERE IS YOUR ACCOUNT INFORMATION & PERFORMANCE\n")
        append("Current Total Return (percent): ${PromptFormatter.formatPercent(marketState.account.totalReturn)}\n")
        append("Available Cash (USD): ${PromptFormatter.formatMoneyUsd(marketState.account.availableCash)}\n")
        append("Current Account Value (USD): ${PromptFormatter.formatMoneyUsd(marketState.account.accountValue)}\n")

        if (marketState.positions.isNotEmpty()) {
            append("Current live positions & performance: ")
            append(PromptFormatter.formatPositions(marketState.positions))
            append("\n")
        }

        marketState.account.sharpeRatio?.let {
            append("Sharpe Ratio: ${PromptFormatter.formatNumber(it)}\n")
        }
    }
}
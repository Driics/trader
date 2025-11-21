package ru.driics.aitrade.domain.services

import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.MarketState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pure domain service for building trading prompts.
 * No infrastructure dependencies.
 */
object PromptBuilder {

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
        .withZone(ZoneId.of("UTC"))

    fun build(
        marketState: MarketState,
        sessionStartTime: Long,
        invocationCount: Long,
        clock: Clock = Clock.systemUTC()
    ): String = buildString {
        val now = clock.instant()
        val duration = (now.toEpochMilli() - sessionStartTime).milliseconds
        val timeStr = DATE_FORMATTER.format(now)

        appendHeader(duration.inWholeMinutes, timeStr, invocationCount)
        appendMarketData(marketState)
        appendAccountInfo(marketState)
    }

    fun buildMarketDataSection(marketState: MarketState): String = buildString {
        appendMarketData(marketState)
    }

    fun buildAccountInfoSection(marketState: MarketState): String = buildString {
        appendAccountInfo(marketState)
    }

    // =========================================================================
    // Private Builders
    // =========================================================================

    private fun StringBuilder.appendHeader(minutes: Long, time: String, count: Long) {
        appendLine("""
            It has been $minutes minutes since you started trading. The current time is $time and you've been invoked $count times.
            Below, we are providing you with a variety of state data, price data, and predictive signals so you can discover alpha.
            Below that is your current account information, value, performance, positions, etc.
            ALL OF THE PRICE OR SIGNAL DATA BELOW IS ORDERED: OLDEST → NEWEST
            Timeframes note: Unless stated otherwise in a section title, intraday series are provided at 3‑minute intervals. If a coin uses a different interval, it is explicitly stated in that coin's section.
        """.trimIndent())
        appendLine()
    }

    private fun StringBuilder.appendMarketData(marketState: MarketState) {
        appendLine("CURRENT MARKET STATE FOR ALL COINS")

        marketState.currencies.forEach { (symbol, data) ->
            appendCurrencyData(symbol, data)
            appendLine()
        }
    }

    private fun StringBuilder.appendCurrencyData(symbol: String, data: CurrencyMarketData) = with(data) {
        appendLine("ALL $symbol DATA")
        appendLine("current_price = ${PromptFormatter.formatNumber(currentPrice)}, " +
                "current_ema20 = ${PromptFormatter.formatNumber(currentEma20)}, " +
                "current_macd = ${PromptFormatter.formatNumber(currentMacd)}, " +
                "current_rsi (7 period) = ${PromptFormatter.formatNumber(currentRsi7)}")

        // Derivatives Data
        if (openInterest != null || fundingRate != null) {
            appendLine("In addition, here is the latest $symbol open interest and funding rate for perps (the instrument you are trading):")
            openInterest?.let {
                val fmt = PromptFormatter.formatNumber(it)
                appendLine("Open Interest: Latest: $fmt Average: $fmt")
            }
            fundingRate?.let {
                appendLine("Funding Rate: ${PromptFormatter.formatScientific(it)}")
            }
        }

        // Intraday Series
        if (intradayPrices.isNotEmpty()) {
            appendLine("Intraday series (3‑minute intervals, oldest → latest):")
            appendLine("Mid prices: ${PromptFormatter.formatNumberList(intradayPrices)}")

            if (intradayEma20.isNotEmpty()) appendLine("EMA indicators (20‑period): ${PromptFormatter.formatNumberList(intradayEma20)}")
            if (intradayMacd.isNotEmpty())  appendLine("MACD indicators: ${PromptFormatter.formatNumberList(intradayMacd)}")
            if (intradayRsi7.isNotEmpty())  appendLine("RSI indicators (7‑Period): ${PromptFormatter.formatNumberList(intradayRsi7)}")
            if (intradayRsi14.isNotEmpty()) appendLine("RSI indicators (14‑Period): ${PromptFormatter.formatNumberList(intradayRsi14)}")
        }

        // 4H Context
        appendLongTermContext(data)
    }

    private fun StringBuilder.appendLongTermContext(data: CurrencyMarketData) = with(data) {
        // Check if any relevant 4H data exists
        if (ema20_4h == null && ema50_4h == null) return

        appendLine("Longer‑term context (4‑hour timeframe):")

        if (ema20_4h != null && ema50_4h != null) {
            appendLine("20‑Period EMA: ${PromptFormatter.formatNumber(ema20_4h)} vs. 50‑Period EMA: ${PromptFormatter.formatNumber(ema50_4h)}")
        }

        if (atr3_4h != null && atr14_4h != null) {
            appendLine("3‑Period ATR: ${PromptFormatter.formatNumber(atr3_4h)} vs. 14‑Period ATR: ${PromptFormatter.formatNumber(atr14_4h)}")
        }

        if (volume4h != null && avgVolume4h != null) {
            appendLine("Current Volume: ${PromptFormatter.formatNumber(volume4h)} vs. Average Volume: ${PromptFormatter.formatNumber(avgVolume4h)}")
        }

        if (macd4h.isNotEmpty()) {
            appendLine("MACD indicators: ${PromptFormatter.formatNumberList(macd4h)}")
        }

        if (rsi14_4h.isNotEmpty()) {
            appendLine("RSI indicators (14‑Period): ${PromptFormatter.formatNumberList(rsi14_4h)}")
        }
    }

    private fun StringBuilder.appendAccountInfo(marketState: MarketState) = with(marketState.account) {
        appendLine("HERE IS YOUR ACCOUNT INFORMATION & PERFORMANCE")
        appendLine("Current Total Return (percent): ${PromptFormatter.formatPercent(totalReturn)}")
        appendLine("Available Cash (USD): ${PromptFormatter.formatMoneyUsd(availableCash)}")
        appendLine("Current Account Value (USD): ${PromptFormatter.formatMoneyUsd(accountValue)}")

        if (marketState.positions.isNotEmpty()) {
            append("Current live positions & performance: ")
            appendLine(PromptFormatter.formatPositions(marketState.positions))
        }

        sharpeRatio?.let {
            appendLine("Sharpe Ratio: ${PromptFormatter.formatNumber(it)}")
        }
    }
}
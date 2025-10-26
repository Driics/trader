package ru.driics.aitrade.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.PromptProperties
import ru.driics.aitrade.model.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Service
class PromptBuilderService(
    private val promptProperties: PromptProperties
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

        val builder = StringBuilder()

        // Header
        builder.append("It has been $minutesSinceStart minutes since you started trading. ")
        builder.append("The current time is $currentTime and you've been invoked $invocationCount times. ")
        builder.append("Below, we are providing you with a variety of state data, price data, and predictive signals so you can discover alpha. ")
        builder.append("Below that is your current account information, value, performance, positions, etc. ")
        builder.append("ALL OF THE PRICE OR SIGNAL DATA BELOW IS ORDERED: OLDEST → NEWEST\n")

        // Timeframes note
        builder.append("Timeframes note: Unless stated otherwise in a section title, intraday series are provided at 3‑minute intervals. ")
        builder.append("If a coin uses a different interval, it is explicitly stated in that coin's section.\n\n")

        // Current Market State
        builder.append("CURRENT MARKET STATE FOR ALL COINS\n")

        for ((symbol, data) in marketState.currencies) {
            builder.append("ALL $symbol DATA\n")
            builder.append("current_price = ${formatNumber(data.currentPrice)}, ")
            builder.append("current_ema20 = ${formatNumber(data.currentEma20)}, ")
            builder.append("current_macd = ${formatNumber(data.currentMacd)}, ")
            builder.append("current_rsi (7 period) = ${formatNumber(data.currentRsi7)}\n")

            // Open Interest and Funding Rate
            if (data.openInterest != null || data.fundingRate != null) {
                builder.append("In addition, here is the latest $symbol open interest and funding rate for perps (the instrument you are trading):\n")
                data.openInterest?.let {
                    builder.append("Open Interest: Latest: ${formatNumber(it)} Average: ${formatNumber(it)}\n")
                }
                data.fundingRate?.let {
                    builder.append("Funding Rate: ${formatScientific(it)}\n")
                }
            }

            // Intraday series
            if (data.intradayPrices.isNotEmpty()) {
                builder.append("Intraday series (by minute, oldest → latest):\n")
                builder.append("Mid prices: ${formatNumberList(data.intradayPrices)}\n")

                if (data.intradayEma20.isNotEmpty()) {
                    builder.append("EMA indicators (20‑period): ${formatNumberList(data.intradayEma20)}\n")
                }
                if (data.intradayMacd.isNotEmpty()) {
                    builder.append("MACD indicators: ${formatNumberList(data.intradayMacd)}\n")
                }
                if (data.intradayRsi7.isNotEmpty()) {
                    builder.append("RSI indicators (7‑Period): ${formatNumberList(data.intradayRsi7)}\n")
                }
                if (data.intradayRsi14.isNotEmpty()) {
                    builder.append("RSI indicators (14‑Period): ${formatNumberList(data.intradayRsi14)}\n")
                }
            }

            // 4-hour context
            if (data.ema20_4h != null || data.ema50_4h != null) {
                builder.append("Longer‑term context (4‑hour timeframe):\n")

                if (data.ema20_4h != null && data.ema50_4h != null) {
                    builder.append("20‑Period EMA: ${formatNumber(data.ema20_4h)} vs. 50‑Period EMA: ${formatNumber(data.ema50_4h)}\n")
                }

                if (data.atr3_4h != null && data.atr14_4h != null) {
                    builder.append("3‑Period ATR: ${formatNumber(data.atr3_4h)} vs. 14‑Period ATR: ${formatNumber(data.atr14_4h)}\n")
                }

                if (data.volume4h != null && data.avgVolume4h != null) {
                    builder.append("Current Volume: ${formatNumber(data.volume4h)} vs. Average Volume: ${formatNumber(data.avgVolume4h)}\n")
                }

                if (data.macd4h.isNotEmpty()) {
                    builder.append("MACD indicators: ${formatNumberList(data.macd4h)}\n")
                }

                if (data.rsi14_4h.isNotEmpty()) {
                    builder.append("RSI indicators (14‑Period): ${formatNumberList(data.rsi14_4h)}\n")
                }
            }

            builder.append("\n")
        }

        // Account Information
        builder.append("HERE IS YOUR ACCOUNT INFORMATION & PERFORMANCE\n")
        builder.append("Current Total Return (percent): ${formatPercent(marketState.account.totalReturn)}\n")
        builder.append("Available Cash: ${formatNumber(marketState.account.availableCash)}\n")
        builder.append("Current Account Value: ${formatNumber(marketState.account.accountValue)}\n")

        // Positions
        if (marketState.positions.isNotEmpty()) {
            builder.append("Current live positions & performance: ")
            builder.append(formatPositions(marketState.positions))
            builder.append("\n")
        }

        // Sharpe Ratio
        marketState.account.sharpeRatio?.let {
            builder.append("Sharpe Ratio: ${formatNumber(it)}\n")
        }

        return builder.toString()
    }

    fun writePromptToFile(prompt: String): Boolean {
        return try {
            val file = java.io.File(promptProperties.outputPath)
            file.parentFile?.mkdirs()
            file.writeText(prompt)
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

    private fun formatNumber(num: BigDecimal?): String {
        if (num == null || num == BigDecimal.ZERO) return "0"
        
        val abs = num.abs()
        return when {
            abs >= BigDecimal(1000000) -> num.setScale(2, java.math.RoundingMode.HALF_UP).toString()
            abs >= BigDecimal(1) -> num.setScale(if (abs >= BigDecimal(100)) 0 else 2, java.math.RoundingMode.HALF_UP).toString()
            abs >= BigDecimal(0.01) -> num.setScale(4, java.math.RoundingMode.HALF_UP).toString()
            else -> num.setScale(8, java.math.RoundingMode.HALF_UP).toString()
        }
    }

    private fun formatScientific(num: BigDecimal?): String {
        if (num == null) return "0"
        return num.toPlainString()
    }

    private fun formatPercent(num: BigDecimal?): String {
        if (num == null || num == BigDecimal.ZERO) return "0%"
        return "${num.setScale(2, java.math.RoundingMode.HALF_UP)}%"
    }

    private fun formatNumberList(numbers: List<BigDecimal>): String {
        return "[${numbers.joinToString(", ") { formatNumber(it) }}]"
    }

    private fun formatPositions(positions: List<Position>): String {
        if (positions.isEmpty()) return "{}"
        
        return positions.joinToString(", ") { pos ->
            buildString {
                append("{'symbol': '${pos.symbol}', ")
                append("'quantity': ${formatNumber(pos.quantity)}, ")
                append("'entry_price': ${formatNumber(pos.entryPrice)}, ")
                append("'current_price': ${formatNumber(pos.currentPrice)}, ")
                if (pos.liquidationPrice != null) {
                    append("'liquidation_price': ${formatNumber(pos.liquidationPrice)}, ")
                }
                append("'unrealized_pnl': ${formatNumber(pos.unrealizedPnl)}, ")
                if (pos.leverage != null) {
                    append("'leverage': ${pos.leverage}, ")
                }
                if (pos.exitPlan != null) {
                    append("'exit_plan': ")
                    append("{'profit_target': ${formatNumber(pos.exitPlan.profitTarget)}, ")
                    append("'stop_loss': ${formatNumber(pos.exitPlan.stopLoss)}, ")
                    append("'invalidation_condition': '${pos.exitPlan.invalidationCondition}'}, ")
                }
                if (pos.confidence != null) {
                    append("'confidence': ${formatNumber(pos.confidence)}, ")
                }
                if (pos.riskUsd != null) {
                    append("'risk_usd': ${formatNumber(pos.riskUsd)}, ")
                }
                if (pos.slOid != null) {
                    append("'sl_oid': ${pos.slOid}, ")
                }
                if (pos.tpOid != null) {
                    append("'tp_oid': ${pos.tpOid}, ")
                }
                append("'wait_for_fill': ${pos.waitForFill}, ")
                if (pos.entryOid != null) {
                    append("'entry_oid': ${pos.entryOid}, ")
                }
                if (pos.notionalUsd != null) {
                    append("'notional_usd': ${formatNumber(pos.notionalUsd)}")
                } else {
                    setLength(length - 2) // Remove trailing ", "
                }
                append("}")
            }
        }
    }
}
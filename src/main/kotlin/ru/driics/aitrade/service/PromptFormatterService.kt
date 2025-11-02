package ru.driics.aitrade.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.driics.aitrade.model.Position
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class PromptFormatterService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    fun formatNumber(num: BigDecimal?): String {
        if (num == null || num == BigDecimal.ZERO) return "0"

        val abs = num.abs()
        return when {
            abs >= BigDecimal(1000000) ->
                num.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            abs >= BigDecimal(100) ->
                num.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            abs >= BigDecimal(1) ->
                num.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            abs >= BigDecimal("0.01") ->
                num.setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            else ->
                num.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
    }

    fun formatMoneyUsd(num: BigDecimal?): String {
        if (num == null) return "$0.00"
        val bd = num.setScale(2, RoundingMode.HALF_UP)
        val nf = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US)
        return nf.format(bd)
    }

    fun formatScientific(num: BigDecimal?): String {
        if (num == null || num == BigDecimal.ZERO) return "0"

        val abs = num.abs()
        return if (abs < BigDecimal("0.0001")) {
            String.format("%.2e", num.toDouble())
        } else {
            num.stripTrailingZeros().toPlainString()
        }
    }

    fun formatPercent(num: BigDecimal?): String {
        if (num == null || num == BigDecimal.ZERO) return "0%"
        return "${num.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}%"
    }

    fun formatNumberList(numbers: List<BigDecimal>): String {
        return "[${numbers.joinToString(", ") { formatNumber(it) }}]"
    }

    fun formatPositions(positions: List<Position>): String {
        if (positions.isEmpty()) return "{}"

        return try {
            val positionMaps = positions.map { pos ->
                mutableMapOf<String, Any?>(
                    "symbol" to pos.symbol,
                    "quantity" to formatNumber(pos.quantity),
                    "entry_price" to formatNumber(pos.entryPrice),
                    "current_price" to formatNumber(pos.currentPrice),
                    "unrealized_pnl" to formatNumber(pos.unrealizedPnl),
                    "wait_for_fill" to pos.waitForFill
                ).apply {
                    pos.liquidationPrice?.let { put("liquidation_price", formatNumber(it)) }
                    pos.leverage?.let { put("leverage", it) }
                    pos.exitPlan?.let { plan ->
                        put("exit_plan", mapOf(
                            "profit_target" to formatNumber(plan.profitTarget),
                            "stop_loss" to formatNumber(plan.stopLoss),
                            "invalidation_condition" to plan.invalidationCondition
                        ))
                    }
                    pos.confidence?.let { put("confidence", formatNumber(it)) }
                    pos.riskUsd?.let { put("risk_usd", formatNumber(it)) }
                    pos.slOid?.let { put("sl_oid", it) }
                    pos.tpOid?.let { put("tp_oid", it) }
                    pos.entryOid?.let { put("entry_oid", it) }
                    pos.notionalUsd?.let { put("notional_usd", formatNumber(it)) }
                }
            }

            // Single dict for one position, array for multiple
            val jsonString = if (positionMaps.size == 1) {
                objectMapper.writeValueAsString(positionMaps[0])
            } else {
                objectMapper.writeValueAsString(positionMaps)
            }

            jsonString.replace("\"", "'")
        } catch (e: Exception) {
            log.error("Error formatting positions", e)
            "{}"
        }
    }
}
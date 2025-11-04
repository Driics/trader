package ru.driics.aitrade.application.usecase

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.IdGenerator
import ru.driics.aitrade.domain.services.OrderSizingPolicy
import ru.driics.aitrade.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

/**
 * Use case: Execute AI trading decisions.
 * Uses domain services (OrderSizingPolicy, IdGenerator) and ports (TradingPort, MarketDataPort).
 */
class ExecuteAiDecisionsUseCase(
    private val trading: TradingPort,
    private val market: MarketDataPort,
    private val tradingProperties: TradingProperties
) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val minConfidence = BigDecimal("0.60")
    }

    private val mapper = jacksonObjectMapper()
    private val sizingPolicy = OrderSizingPolicy(
        takerFeePct = tradingProperties.takerFeePct,
        marginBufferPct = tradingProperties.marginBufferPct
    )

    suspend fun execute(aiJson: String): List<AiTradeExecutionResult> = coroutineScope {
        log.info { "Executing AI trading decisions" }

        val supported = tradingProperties.getCurrenciesList().map { it.uppercase(Locale.ROOT) }.toSet()

        val parsed: AiTradeDecisionMap = try {
            mapper.readValue(aiJson)
        } catch (e: Exception) {
            log.error(e) { "Failed to parse AI JSON" }
            return@coroutineScope listOf(
                AiTradeExecutionResult("*", AIAction.SKIPPED, "Invalid AI JSON: ${e.message}")
            )
        }

        log.info { "Parsed ${parsed.size} AI decisions" }

        val state = market.loadMarketState(supported.toList())
        var remainingCashUsd = state.account.availableCash.max(BigDecimal.ZERO)

        val planResults = parsed.values.map { env ->
            async {
                buildPlan(env.args, supported)
            }
        }.awaitAll()

        val results = mutableListOf<AiTradeExecutionResult>()

        for (res in planResults) {
            when (res) {
                is PlanResult.Skip -> {
                    log.debug { "${res.symbol}: ${res.reason}" }
                    results += AiTradeExecutionResult(
                        symbol = res.symbol,
                        action = AIAction.SKIPPED,
                        message = res.reason
                    )
                }

                is PlanResult.Ready -> {
                    val plan = res.plan
                    val result = executePlan(plan, remainingCashUsd)

                    if (result.action == AIAction.PLACED) {
                        // Deduct used capital
                        val usedUsd = extractUsedUsd(result.message)
                        remainingCashUsd = (remainingCashUsd - usedUsd).max(BigDecimal.ZERO)
                    }

                    results += result
                }
            }
        }

        logExecutionSummary(results)
        return@coroutineScope results
    }

    private sealed class PlanResult {
        data class Skip(val symbol: String, val reason: String) : PlanResult()
        data class Ready(val plan: OrderPlan) : PlanResult()
    }

    private data class OrderPlan(
        val symbol: String,
        val instId: String,
        val side: String,
        val leverage: Int,
        val coinQty: BigDecimal,
        val entryPx: BigDecimal,
        val ctVal: BigDecimal,
        val ctValCcy: String,
        val minSz: BigDecimal,
        val lotSz: BigDecimal,
        val tickSz: BigDecimal,
        val tpPx: BigDecimal?,
        val slPx: BigDecimal?
    )

    private suspend fun buildPlan(args: AiTradeSignalArgs, supported: Set<String>): PlanResult {
        val symbol = args.coin.uppercase(Locale.ROOT)
        if (symbol !in supported) return PlanResult.Skip(symbol, "Not in configured list")

        val signal = args.signal.lowercase(Locale.ROOT)
        if (signal == "hold") return PlanResult.Skip(symbol, "Hold signal")
        if (signal !in setOf("buy", "sell")) return PlanResult.Skip(symbol, "Unsupported signal: ${args.signal}")

        val confidence = args.confidence ?: BigDecimal.ZERO
        if (confidence < minConfidence) {
            return PlanResult.Skip(symbol, "Confidence $confidence < $minConfidence")
        }

        val instId = "${symbol}-USDT-SWAP"
        val inst = trading.loadInstrument(instId)
            ?: return PlanResult.Skip(symbol, "No instrument info")

        val entryPx = trading.getLastPrice(instId)
            ?: return PlanResult.Skip(symbol, "No price available")

        val sl = args.stopLoss
        val tp = args.profitTarget

        val coinQty = when {
            (args.quantity ?: BigDecimal.ZERO) > BigDecimal.ZERO -> args.quantity!!
            args.riskUsd != null && sl != null && sl > BigDecimal.ZERO -> {
                val riskPerUnit = (entryPx - sl).abs()
                if (riskPerUnit > BigDecimal.ZERO)
                    args.riskUsd.divide(riskPerUnit, 8, RoundingMode.HALF_UP)
                else BigDecimal.ZERO
            }
            else -> BigDecimal.ZERO
        }

        if (coinQty <= BigDecimal.ZERO) {
            return PlanResult.Skip(symbol, "Zero/unknown quantity")
        }

        val lev = (args.leverage ?: 10).coerceIn(5, 40)
        val tick = inst.tickSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("0.01")
        val lot = inst.lotSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
        val min = inst.minSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: lot
        val ctVal = inst.ctVal?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?: return PlanResult.Skip(symbol, "Invalid ctVal")
        val ccy = (inst.ctValCcy ?: "").uppercase(Locale.ROOT)
        val side = if (signal == "buy") "buy" else "sell"

        return PlanResult.Ready(
            OrderPlan(
                symbol = symbol,
                instId = instId,
                side = side,
                leverage = lev,
                coinQty = coinQty,
                entryPx = entryPx,
                ctVal = ctVal,
                ctValCcy = ccy,
                minSz = min,
                lotSz = lot,
                tickSz = tick,
                tpPx = tp,
                slPx = sl
            )
        )
    }

    private suspend fun executePlan(plan: OrderPlan, availableUsd: BigDecimal): AiTradeExecutionResult {
        val sizingInput = OrderSizingPolicy.SizingInput(
            coinQty = plan.coinQty,
            entryPx = plan.entryPx,
            ctVal = plan.ctVal,
            ctValCcy = plan.ctValCcy,
            lotSz = plan.lotSz,
            minSz = plan.minSz,
            leverage = plan.leverage,
            availableUsd = availableUsd
        )

        val sizing = sizingPolicy.size(sizingInput)
        if (sizing == null) {
            return AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.SKIPPED,
                message = "Insufficient margin",
                instId = plan.instId
            )
        }

        val levOk = trading.setCrossLeverage(plan.instId, sizing.leverage)
        if (!levOk) {
            return AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.SKIPPED,
                message = "Failed to set leverage ${sizing.leverage}",
                instId = plan.instId
            )
        }

        val clId = IdGenerator.clOrdId(plan.symbol)
        val tag = IdGenerator.safeTag("ai-signal")

        val outcome = trading.placeMarketOrderWithTpSl(
            instId = plan.instId,
            side = plan.side,
            contracts = sizing.roundedContracts,
            tp = plan.tpPx,
            sl = plan.slPx,
            tickSz = plan.tickSz,
            clOrdId = clId,
            tag = tag
        )

        return if (outcome.ok) {
            val orderDetails = buildString {
                append("${plan.side.uppercase()} ${sizing.roundedContracts} contracts @ ${plan.entryPx.stripTrailingZeros().toPlainString()}")
                append(" | Leverage: ${sizing.leverage}x")
                plan.tpPx?.let { append(" | TP: ${it.stripTrailingZeros().toPlainString()}") }
                plan.slPx?.let { append(" | SL: ${it.stripTrailingZeros().toPlainString()}") }
                append(" | Cost: $${sizing.totalUsd.setScale(2, RoundingMode.HALF_UP)}")
            }

            log.info { "✓ ${plan.symbol}: $orderDetails" }

            AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.PLACED,
                message = "Order placed (${sizing.totalUsd.setScale(2, RoundingMode.HALF_UP)} USD)",
                instId = plan.instId,
                clOrdId = clId,
                ordId = outcome.ordId,
                requestedContracts = sizing.requestedContracts,
                placedContracts = sizing.roundedContracts
            )
        } else {
            log.warn { "✗ ${plan.symbol}: Order rejected - ${outcome.message}" }
            AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.SKIPPED,
                message = "Rejected: ${outcome.message}",
                instId = plan.instId,
                clOrdId = clId,
                requestedContracts = sizing.requestedContracts
            )
        }
    }

    private fun extractUsedUsd(message: String): BigDecimal {
        return try {
            val regex = """(\d+\.?\d*)\s*USD""".toRegex()
            regex.find(message)?.groupValues?.get(1)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        } catch (e: Exception) {
            BigDecimal.ZERO
        }
    }

    private fun logExecutionSummary(results: List<AiTradeExecutionResult>) {
        val placed = results.count { it.action == AIAction.PLACED }
        val skipped = results.count { it.action == AIAction.SKIPPED }

        log.info { "═══ Execution Summary: $placed placed, $skipped skipped ═══" }
    }

    private fun BigDecimal.max(other: BigDecimal) = if (this >= other) this else other
}
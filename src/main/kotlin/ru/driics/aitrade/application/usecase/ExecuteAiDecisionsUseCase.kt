package ru.driics.aitrade.application.usecase

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.util.isPositive
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.IdGenerator
import ru.driics.aitrade.domain.services.OrderSizingPolicy
import ru.driics.aitrade.domain.types.asSymbol
import ru.driics.aitrade.domain.types.getOrNull
import ru.driics.aitrade.domain.types.getOrThrow
import ru.driics.aitrade.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

class ExecuteAiDecisionsUseCase(
    private val trading: TradingPort,
    private val market: MarketDataPort,
    private val tradingProperties: TradingProperties
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val minConfidence = tradingProperties.minConfidence
    private val maxLev = tradingProperties.maxLeverage
    private val minLev = tradingProperties.minLeverage

    private val sizingPolicy = OrderSizingPolicy(
        takerFeePct = tradingProperties.takerFeePct,
        marginBufferPct = tradingProperties.marginBufferPct
    )

    suspend fun execute(decisions: AiTradeDecisionMap): List<AiTradeExecutionResult> = coroutineScope {
        log.info { "Executing AI trading decisions" }

        val supported = tradingProperties.getCurrenciesList().map { it.asSymbol().value }.toSet()
        val state = market.loadMarketState(supported.toList())

        var remainingCashUsd = maxOf(state.account.availableCash, BigDecimal.ZERO)

        val planResults = decisions.values.map { env ->
            async(Dispatchers.Default) {
                buildPlan(env.args, supported)
            }
        }.awaitAll()

        val results = mutableListOf<AiTradeExecutionResult>()

        val semaphore = Semaphore(3)

        // TODO: return calc remainingCashUsd and logging PlanResult.Skip
        results.addAll(planResults.filterIsInstance<PlanResult.Ready>().map { ready ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    executePlan(ready.plan, state.account.availableCash)
                }
            }
        }.awaitAll())

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
        val symbol = args.coin.asSymbol().value
        if (symbol !in supported) return PlanResult.Skip(symbol, "Not in configured list")

        if (args.signal == AiSignal.HOLD) return PlanResult.Skip(symbol, "Hold signal")

        val confidence = args.confidence ?: BigDecimal.ZERO
        if (confidence < minConfidence) {
            return PlanResult.Skip(symbol, "Confidence $confidence < $minConfidence")
        }

        val instId = "${symbol}-USDT-SWAP"
        val inst = trading.loadInstrument(instId).getOrNull()
            ?: return PlanResult.Skip(symbol, "No instrument info")

        val entryPx = trading.getLastPrice(instId).getOrNull()
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

        val lev = (args.leverage ?: 10).coerceIn(minLev, maxLev)
        val tick = inst.tickSz?.toBigDecimalOrNull()?.takeIf { it.isPositive() } ?: BigDecimal("0.01")
        val lot = inst.lotSz?.toBigDecimalOrNull()?.takeIf { it.isPositive() } ?: BigDecimal.ONE
        val min = inst.minSz?.toBigDecimalOrNull()?.takeIf { it.isPositive() } ?: lot
        val ctVal = inst.ctVal?.toBigDecimalOrNull()?.takeIf { it.isPositive() }
            ?: return PlanResult.Skip(symbol, "Invalid ctVal")
        val ccy = (inst.ctValCcy ?: "").uppercase(Locale.ROOT)

        val side = when (args.signal) {
            AiSignal.BUY -> "buy"
            AiSignal.SELL -> "sell"
            AiSignal.HOLD -> return PlanResult.Skip(symbol, "Hold signal")
        }

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

        val sizing = sizingPolicy.size(sizingInput) ?: return AiTradeExecutionResult(
            symbol = plan.symbol,
            action = AIAction.SKIPPED,
            message = "Insufficient margin",
            instId = plan.instId
        )

        val marginMode = tradingProperties.getMarginMode()
        val levOk = trading.setLeverage(plan.instId, sizing.leverage, marginMode).getOrThrow()
        if (!levOk) {
            return AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.SKIPPED,
                message = "Failed to set leverage ${sizing.leverage} (${marginMode.asOkxApiValue})",
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
            tag = tag,
            marginMode = marginMode
        ).getOrThrow()

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
            log.debug(e) { "Failed to parse USD amount from message: $message" }
            BigDecimal.ZERO
        }
    }

    private fun logExecutionSummary(results: List<AiTradeExecutionResult>) {
        val placed = results.count { it.action == AIAction.PLACED }
        val skipped = results.count { it.action == AIAction.SKIPPED }

        log.info { "═══ Execution Summary: $placed placed, $skipped skipped ═══" }
    }
}
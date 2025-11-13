package ru.driics.aitrade.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.IdGenerator
import ru.driics.aitrade.domain.services.OrderSizingPolicy
import ru.driics.aitrade.domain.types.InstrumentId
import ru.driics.aitrade.domain.types.asSymbol
import ru.driics.aitrade.domain.types.getOrNull
import ru.driics.aitrade.domain.types.getOrThrow
import ru.driics.aitrade.domain.util.isPositive
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.time.Duration.Companion.seconds

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

        val supported = tradingProperties.getCurrenciesList().map { it }.toSet()
        // Load current state with timeout
        val state = withTimeout(30.seconds) {
            market.loadMarketState(supported.map { it.asSymbol() })
        }

        // Build plans in parallel with error handling
        val planResults = decisions.values.map { env ->
            async(Dispatchers.Default) {
                runCatching {
                    buildPlan(env.args, supported)
                }.getOrElse { e ->
                    log.error(e) { "Failed to build plan for ${env.args.coin}" }
                    PlanResult.Skip(env.args.coin, "Plan build error: ${e.message}")
                }
            }
        }.awaitAll()

        val results = mutableListOf<AiTradeExecutionResult>()

        // Log skipped plans
        planResults.filterIsInstance<PlanResult.Skip>().forEach { skip ->
            log.info { "Skipping ${skip.symbol}: ${skip.reason}" }
        }

        val semaphore = Semaphore(tradingProperties.maxConcurrentSymbols)

        // Execute ready plans in parallel with semaphore
        val execResults = planResults.filterIsInstance<PlanResult.Ready>().map { ready ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching {
                        executePlan(ready.plan, state.account.availableCash)
                    }.getOrElse { e ->
                        log.error(e) { "Failed to execute plan for ${ready.plan.symbol}" }
                        AiTradeExecutionResult(
                            symbol = ready.plan.symbol,
                            action = AIAction.SKIPPED,
                            message = "Error:: ${e.message}",
                            instId = ready.plan.instId,
                            clOrdId = "",
                            requestedContracts = BigDecimal.ZERO
                        )
                    }
                }
            }
        }.awaitAll()

        results.addAll(execResults)
        results
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
        val symbol = args.coin
        if (symbol !in supported) return PlanResult.Skip(symbol, "Not in configured list")

        if (args.signal == AiSignal.HOLD) return PlanResult.Skip(symbol, "Hold signal")

        val confidence = args.confidence ?: BigDecimal.ZERO
        if (confidence < minConfidence) {
            return PlanResult.Skip(symbol, "Confidence $confidence < $minConfidence")
        }

        val instrumentId = InstrumentId.fromSymbol(symbol)
        val inst = trading.loadInstrument(instrumentId).getOrThrow()

        val entryPx = trading.getLastPrice(instrumentId).getOrNull()
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
                instId = instrumentId.value,
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
        val levOk = trading.setLeverage(InstrumentId.fromSymbol(plan.instId), sizing.leverage, marginMode).getOrThrow()
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
            instrumentId = InstrumentId.fromSymbol(plan.instId),
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
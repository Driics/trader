package ru.driics.aitrade.application.usecase

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.driics.aitrade.application.ai.ActionGuard
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.ai.IdempotencyService
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.common.logging.logger
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
import ru.driics.aitrade.domain.util.isZeroOrNegative
import ru.driics.aitrade.domain.util.quantizeToLot
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.*
import kotlin.time.Duration.Companion.seconds

class ExecuteAiDecisionsUseCase(
    private val trading: TradingPort,
    private val market: MarketDataPort,
    private val tradingProperties: TradingProperties,
    private val clock: Clock,
    private val confidenceCalibrator: ConfidenceCalibrator
) {
    companion object {
        private val log = logger<ExecuteAiDecisionsUseCase>()
        private const val MARKET_STATE_TIMEOUT_SECONDS = 30L
    }

    private val minConfidence = tradingProperties.minConfidence
    private val maxLev = tradingProperties.maxLeverage
    private val minLev = tradingProperties.minLeverage

    private val sizingPolicy = OrderSizingPolicy(
        takerFeePct = tradingProperties.takerFeePct,
        marginBufferPct = tradingProperties.marginBufferPct
    )

    private val actionGuard = ActionGuard(tradingProperties)
    private val idempotencyService = IdempotencyService(clock)

    suspend fun execute(decisions: AiTradeDecisionMap): List<AiTradeExecutionResult> = coroutineScope {
        log.info { "Executing AI trading decisions" }

        val supported = tradingProperties.getCurrenciesList().toSet()
        // Load current state with timeout
        val state = withTimeout(MARKET_STATE_TIMEOUT_SECONDS.seconds) {
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

        // Log skipped plans
        planResults.filterIsInstance<PlanResult.Skip>().forEach { skip ->
            log.info { "Skipping ${skip.symbol}: ${skip.reason}" }
        }

        val semaphore = Semaphore(tradingProperties.maxConcurrentSymbols)
        val readyPlans = planResults.filterIsInstance<PlanResult.Ready>()

        // Execute ready plans in parallel with semaphore
        readyPlans.map { ready ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching {
                        executePlan(ready.plan, state.account.availableCash)
                    }.getOrElse { e ->
                        log.error(e) { "Failed to execute plan for ${ready.plan.symbol}" }
                        createErrorResult(ready.plan, e.message ?: "Unknown error")
                    }
                }
            }
        }.awaitAll()
    }

    private sealed class PlanResult {
        data class Skip(val symbol: String, val reason: String) : PlanResult()
        data class Ready(val plan: OrderPlan) : PlanResult()
    }

    private data class OrderPlan(
        val symbol: String,
        val instrumentId: InstrumentId,
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

        val lastPrice = trading.getLastPrice(instrumentId).getOrNull()
            ?: return PlanResult.Skip(symbol, "No price available")

        // Check idempotency (deduplication)
        val signalKey = idempotencyService.signalKey(symbol, args, lastPrice)
        if (idempotencyService.isDuplicate(signalKey)) {
            return PlanResult.Skip(symbol, "Duplicate signal (idempotency check)")
        }

        // Apply guardrails validation
        val validation = actionGuard.validate(
            plan = args,
            instrumentInfo = inst,
            lastPrice = lastPrice
        )

        when (validation) {
            is ActionGuard.ValidationResult.Rejected -> {
                BusinessEventLogger.orderRejected(
                    symbol = symbol,
                    clOrdId = null,
                    reason = validation.reason,
                    errorCode = "GUARD_VIOLATION"
                )
                return PlanResult.Skip(symbol, "Guard violation: ${validation.reason}")
            }
            is ActionGuard.ValidationResult.Valid -> {
                // Extract instrument parameters
                val tick = extractInstrumentValue(inst.tickSz, BigDecimal("0.01"))
                val lot = extractInstrumentValue(inst.lotSz, BigDecimal.ONE)
                val min = extractInstrumentValue(inst.minSz, lot)
                val ctVal = extractInstrumentValue(inst.ctVal, null)
                    ?: return PlanResult.Skip(symbol, "Invalid ctVal")
                val ccy = (inst.ctValCcy ?: "").uppercase(Locale.ROOT)

                // Calculate quantity if not provided
                val coinQty = validation.quantizedQuantity ?: calculateQuantityFromRisk(
                    args.riskUsd,
                    validation.quantizedEntry,
                    validation.quantizedSl
                )

                if (coinQty.isZeroOrNegative()) {
                    return PlanResult.Skip(symbol, "Zero/unknown quantity")
                }

                // Record signal for idempotency
                idempotencyService.recordSignal(signalKey)

                return PlanResult.Ready(
                    OrderPlan(
                        symbol = symbol,
                        instrumentId = instrumentId,
                        side = validation.normalizedSignal,
                        leverage = validation.leverage,
                        coinQty = coinQty,
                        entryPx = validation.quantizedEntry,
                        ctVal = ctVal,
                        ctValCcy = ccy,
                        minSz = min,
                        lotSz = lot,
                        tickSz = tick,
                        tpPx = validation.quantizedTp,
                        slPx = validation.quantizedSl
                    )
                )
            }
        }
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
            instId = plan.instrumentId.value
        )

        val marginMode = tradingProperties.getMarginMode()
        val levOk = trading.setLeverage(plan.instrumentId, sizing.leverage, marginMode).getOrThrow()
        if (!levOk) {
            return AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.SKIPPED,
                message = "Failed to set leverage ${sizing.leverage} (${marginMode.asOkxApiValue})",
                instId = plan.instrumentId.value
            )
        }

        // Generate deterministic clOrdId for idempotency
        val timestamp = clock.instant().toEpochMilli()
        val signalArgs = AiTradeSignalArgs(
            coin = plan.symbol,
            signal = if (plan.side == "buy") AiSignal.BUY else AiSignal.SELL,
            quantity = plan.coinQty,
            profitTarget = plan.tpPx,
            stopLoss = plan.slPx,
            leverage = plan.leverage
        )
        val clId = idempotencyService.generateClOrdId(plan.symbol, signalArgs, plan.entryPx, timestamp)
        val tag = IdGenerator.safeTag("ai-signal")

        val outcome = trading.placeMarketOrderWithTpSl(
            instrumentId = plan.instrumentId,
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
            // Record trade for cooldown tracking
            confidenceCalibrator.recordTrade(plan.symbol)

            // Log structured business event
            BusinessEventLogger.orderPlaced(
                symbol = plan.symbol,
                orderId = outcome.ordId,
                clOrdId = clId,
                side = plan.side,
                contracts = sizing.roundedContracts,
                price = plan.entryPx,
                tp = plan.tpPx,
                sl = plan.slPx,
                leverage = sizing.leverage,
                costUsd = sizing.totalUsd
            )

            AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.PLACED,
                message = "Order placed (${sizing.totalUsd.setScale(2, RoundingMode.HALF_UP)} USD)",
                instId = plan.instrumentId.value,
                clOrdId = clId,
                ordId = outcome.ordId,
                requestedContracts = sizing.requestedContracts,
                placedContracts = sizing.roundedContracts
            )
        } else {
            BusinessEventLogger.orderRejected(
                symbol = plan.symbol,
                clOrdId = clId,
                reason = outcome.message ?: "Unknown error",
                errorCode = null
            )

            AiTradeExecutionResult(
                symbol = plan.symbol,
                action = AIAction.SKIPPED,
                message = "Rejected: ${outcome.message}",
                instId = plan.instrumentId.value,
                clOrdId = clId,
                requestedContracts = sizing.requestedContracts
            )
        }
    }

    private fun extractInstrumentValue(value: String?, default: BigDecimal?): BigDecimal? =
        value?.toBigDecimalOrNull()?.takeIf { it.isPositive() } ?: default

    private fun calculateQuantityFromRisk(
        riskUsd: BigDecimal?,
        entryPrice: BigDecimal,
        stopLoss: BigDecimal?
    ): BigDecimal {
        if (riskUsd == null || stopLoss == null || stopLoss.isZeroOrNegative()) {
            return BigDecimal.ZERO
        }
        
        val riskPerUnit = (entryPrice - stopLoss).abs()
        return if (riskPerUnit.isPositive()) {
            riskUsd.divide(riskPerUnit, 8, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    }

    private fun createErrorResult(plan: OrderPlan, errorMessage: String): AiTradeExecutionResult =
        AiTradeExecutionResult(
            symbol = plan.symbol,
            action = AIAction.SKIPPED,
            message = "Error: $errorMessage",
            instId = plan.instrumentId.value,
            clOrdId = "",
            requestedContracts = BigDecimal.ZERO
        )
}
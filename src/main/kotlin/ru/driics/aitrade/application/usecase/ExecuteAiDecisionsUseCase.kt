package ru.driics.aitrade.application.usecase

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val meterRegistry: MeterRegistry
) {
    private companion object {
        val log = logger<ExecuteAiDecisionsUseCase>()
        const val MARKET_STATE_TIMEOUT_SECONDS = 30L
        const val GUARD_METRIC_NAME = "guard.rejected"
        const val REASON_TAG_LIMIT = 50
    }

    private val minConfidence = tradingProperties.minConfidence

    private val sizingPolicy = OrderSizingPolicy(
        takerFeePct = tradingProperties.takerFeePct,
        marginBufferPct = tradingProperties.marginBufferPct
    )

    private val actionGuard = ActionGuard(tradingProperties)
    private val idempotencyService = IdempotencyService(clock)

    /**
     * Main execution entry point.
     * - Loads market state
     * - Converts decisions to plans in parallel
     * - Executes plans with concurrency limits
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun execute(decisions: AiTradeDecisionMap): List<AiTradeExecutionResult> = coroutineScope {
        log.info { "Executing AI trading decisions for ${decisions.size} symbols" }

        val supportedSymbols = tradingProperties.getCurrenciesList().toSet()

        // 1. Load Market State (Pre-fetch)
        val state = try {
            withTimeout(MARKET_STATE_TIMEOUT_SECONDS.seconds) {
                market.loadMarketState(supportedSymbols.map { it.asSymbol() })
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load market state within timeout" }
            return@coroutineScope emptyList()
        }

        // 2. Pipeline: Decision -> Plan -> Execution
        decisions.values.asFlow()
            // Step A: Build Plans (Parallel, CPU-bound mostly)
            .map { envelope ->
                async(Dispatchers.Default) {
                    runCatching {
                        buildPlan(envelope.args, supportedSymbols)
                    }.getOrElse { e ->
                        log.error(e) { "Failed to build plan for ${envelope.args.coin}" }
                        PlanResult.Skip(envelope.args.coin, "Build error: ${e.message}")
                    }
                }
            }
            .map { it.await() }
            // Log Skips
            .onEach { result ->
                if (result is PlanResult.Skip) {
                    log.info { "Skipping ${result.symbol}: ${result.reason}" }
                }
            }
            // Filter for Ready plans
            .filterIsInstance<PlanResult.Ready>()
            // Step B: Execute Plans (Concurrent I/O with limit)
            .flatMapMerge(concurrency = tradingProperties.maxConcurrentSymbols) { ready ->
                flow {
                    emit(
                        runCatching {
                            executePlan(ready.plan, state.account.availableCash)
                        }.getOrElse { e ->
                            log.error(e) { "Failed to execute plan for ${ready.plan.symbol}" }
                            createErrorResult(ready.plan, e.message ?: "Unknown execution error")
                        }
                    )
                }
            }
            .toList()
    }

    // =========================================================================
    // Planning Phase
    // =========================================================================

    private suspend fun buildPlan(args: AiTradeSignalArgs, supported: Set<String>): PlanResult {
        val symbol = args.coin

        // 1. Basic Checks
        if (symbol !in supported) return PlanResult.Skip(symbol, "Not in configured list")
        if (args.signal == AiSignal.HOLD) return PlanResult.Skip(symbol, "Hold signal")

        val confidence = args.confidence ?: BigDecimal.ZERO
        if (confidence < minConfidence) {
            return PlanResult.Skip(symbol, "Confidence $confidence < $minConfidence")
        }

        // 2. Load Instrument & Price
        val instrumentId = InstrumentId.fromSymbol(symbol)
        val inst = trading.loadInstrument(instrumentId).getOrThrow()
        val lastPrice = trading.getLastPrice(instrumentId).getOrNull()
            ?: return PlanResult.Skip(symbol, "No price available")

        // 3. Idempotency Check
        val signalKey = idempotencyService.signalKey(symbol, args, lastPrice)
        if (idempotencyService.isDuplicate(signalKey)) {
            return PlanResult.Skip(symbol, "Duplicate signal")
        }

        // 4. Guardrails
        val validation = actionGuard.validate(args, inst, lastPrice)
        if (validation is ActionGuard.ValidationResult.Rejected) {
            recordGuardRejection(validation.reason)
            BusinessEventLogger.orderRejected(symbol, null, validation.reason, "GUARD_VIOLATION")
            return PlanResult.Skip(symbol, "Guard: ${validation.reason}")
        }

        // 5. Valid Plan Construction
        val valid = validation as ActionGuard.ValidationResult.Valid

        // Parse instrument specs safe
        val tick = inst.tickSz.extractPositive() ?: BigDecimal("0.01")
        val lot = inst.lotSz.extractPositive() ?: BigDecimal.ONE
        val min = inst.minSz.extractPositive() ?: lot
        val ctVal = inst.ctVal.extractPositive()
            ?: return PlanResult.Skip(symbol, "Invalid ctVal in instrument")
        val ccy = (inst.ctValCcy ?: "").uppercase(Locale.ROOT)

        // Determine Quantity
        val quantity = valid.quantizedQuantity ?: calculateQuantityFromRisk(
            args.riskUsd,
            valid.quantizedEntry,
            valid.quantizedSl
        )

        if (quantity.isZeroOrNegative()) {
            return PlanResult.Skip(symbol, "Zero quantity calculated")
        }

        return PlanResult.Ready(
            OrderPlan(
                symbol = symbol,
                instrumentId = instrumentId,
                side = valid.normalizedSignal,
                leverage = valid.leverage,
                coinQty = quantity,
                entryPx = valid.quantizedEntry,
                ctVal = ctVal,
                ctValCcy = ccy,
                minSz = min,
                lotSz = lot,
                tickSz = tick,
                tpPx = valid.quantizedTp,
                slPx = valid.quantizedSl,
                signalKey = signalKey
            )
        )
    }

    // =========================================================================
    // Execution Phase
    // =========================================================================

    private suspend fun executePlan(plan: OrderPlan, availableUsd: BigDecimal): AiTradeExecutionResult {
        // 1. Sizing
        val sizing = sizingPolicy.size(
            OrderSizingPolicy.SizingInput(
                coinQty = plan.coinQty,
                entryPx = plan.entryPx,
                ctVal = plan.ctVal,
                ctValCcy = plan.ctValCcy,
                lotSz = plan.lotSz,
                minSz = plan.minSz,
                leverage = plan.leverage,
                availableUsd = availableUsd
            )
        ) ?: return createSkippedResult(plan, "Insufficient margin")

        // 2. Set Leverage
        val marginMode = tradingProperties.getMarginMode()
        val levOk = trading.setLeverage(plan.instrumentId, sizing.leverage, marginMode).getOrThrow()

        if (!levOk) {
            return createSkippedResult(plan, "Failed to set leverage ${sizing.leverage}")
        }

        // 3. Place Order (or Simulate if Demo Mode)
        val clOrdId = idempotencyService.generateClOrdId(
            plan.symbol,
            AiTradeSignalArgs(
                coin = plan.symbol,
                signal = if (plan.side == "buy") AiSignal.BUY else AiSignal.SELL,
                quantity = plan.coinQty,
                profitTarget = plan.tpPx,
                stopLoss = plan.slPx,
                leverage = plan.leverage
            ),
            plan.entryPx,
            clock.instant().toEpochMilli()
        )

        if (tradingProperties.demoMode) {
            log.info { "DEMO MODE: Simulating ${plan.side} order for ${plan.symbol} (Qty: ${sizing.roundedContracts})" }
            // Simulate success
            return handleSuccessfulOrder(
                plan, 
                sizing, 
                clOrdId = "DEMO-$clOrdId", 
                ordId = "DEMO-ORD-${UUID.randomUUID()}"
            )
        }

        val outcome = trading.placeMarketOrderWithTpSl(
            instrumentId = plan.instrumentId,
            side = plan.side,
            contracts = sizing.roundedContracts,
            tp = plan.tpPx,
            sl = plan.slPx,
            tickSz = plan.tickSz,
            clOrdId = clOrdId,
            tag = IdGenerator.safeTag("ai-signal"),
            marginMode = marginMode
        ).getOrThrow()

        return if (outcome.ok) {
            handleSuccessfulOrder(plan, sizing, clOrdId, outcome.ordId)
        } else {
            handleFailedOrder(plan, clOrdId, outcome.message)
        }
    }

    private fun handleSuccessfulOrder(
        plan: OrderPlan,
        sizing: OrderSizingPolicy.SizingResult,
        clOrdId: String,
        ordId: String?
    ): AiTradeExecutionResult {
        // Effects
        confidenceCalibrator.recordTrade(plan.symbol)
        plan.signalKey?.let { idempotencyService.recordSignal(it) }

        BusinessEventLogger.orderPlaced(
            symbol = plan.symbol,
            orderId = ordId,
            clOrdId = clOrdId,
            side = plan.side,
            contracts = sizing.roundedContracts,
            price = plan.entryPx,
            tp = plan.tpPx,
            sl = plan.slPx,
            leverage = sizing.leverage,
            costUsd = sizing.totalUsd
        )

        return AiTradeExecutionResult(
            symbol = plan.symbol,
            action = AIAction.PLACED,
            message = "Placed: ${sizing.totalUsd.setScale(2, RoundingMode.HALF_UP)} USD",
            instId = plan.instrumentId.value,
            clOrdId = clOrdId,
            ordId = ordId,
            requestedContracts = sizing.requestedContracts,
            placedContracts = sizing.roundedContracts
        )
    }

    private fun handleFailedOrder(plan: OrderPlan, clOrdId: String, message: String?): AiTradeExecutionResult {
        BusinessEventLogger.orderRejected(plan.symbol, clOrdId, message ?: "Unknown", null)
        return createSkippedResult(plan, "Rejected: $message", clOrdId)
    }

    // =========================================================================
    // Helpers & Models
    // =========================================================================

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
        val slPx: BigDecimal?,
        val signalKey: String?
    )

    private fun createSkippedResult(plan: OrderPlan, msg: String, clOrdId: String? = null) = AiTradeExecutionResult(
        symbol = plan.symbol,
        action = AIAction.SKIPPED,
        message = msg,
        instId = plan.instrumentId.value,
        clOrdId = clOrdId
    )

    private fun createErrorResult(plan: OrderPlan, msg: String) = createSkippedResult(plan, "Error: $msg")

    private fun calculateQuantityFromRisk(
        riskUsd: BigDecimal?,
        entryPrice: BigDecimal,
        stopLoss: BigDecimal?
    ): BigDecimal {
        if (riskUsd == null || stopLoss == null || stopLoss.isZeroOrNegative()) return BigDecimal.ZERO

        val riskPerUnit = (entryPrice - stopLoss).abs()
        return if (riskPerUnit.isPositive()) {
            riskUsd.divide(riskPerUnit, 8, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    }

    private fun String?.extractPositive(): BigDecimal? =
        this?.toBigDecimalOrNull()?.takeIf { it.isPositive() }

    private fun recordGuardRejection(reason: String) {
        val normalized = reason.take(REASON_TAG_LIMIT)
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .lowercase()
        meterRegistry.counter(GUARD_METRIC_NAME, "reason", normalized).increment()
    }
}
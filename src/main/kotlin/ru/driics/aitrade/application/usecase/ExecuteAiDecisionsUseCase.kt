package ru.driics.aitrade.application.usecase

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.driics.aitrade.application.ai.ActionGuard
import ru.driics.aitrade.application.ai.ConfidenceCalibrator
import ru.driics.aitrade.application.ai.IdempotencyService
import ru.driics.aitrade.application.risk.RiskGate
import ru.driics.aitrade.domain.risk.RiskContext
import ru.driics.aitrade.domain.risk.RiskDecision
import ru.driics.aitrade.common.logging.BusinessEventLogger
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.config.RiskGateProperties
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.StreamingMarketDataPort
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.IdGenerator
import ru.driics.aitrade.domain.services.OrderSizingPolicy
import ru.driics.aitrade.domain.types.InstrumentId
import ru.driics.aitrade.domain.types.getOrNull
import ru.driics.aitrade.domain.types.getOrThrow
import ru.driics.aitrade.domain.util.isPositive
import ru.driics.aitrade.domain.util.isZeroOrNegative
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.*

class ExecuteAiDecisionsUseCase(
    private val trading: TradingPort,
    private val tradingProperties: TradingProperties,
    private val clock: Clock,
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val meterRegistry: MeterRegistry,
    private val riskGate: RiskGate,
    private val riskGateProperties: RiskGateProperties,
    private val streaming: StreamingMarketDataPort,
) {
    private companion object {
        val log = logger<ExecuteAiDecisionsUseCase>()
        const val GUARD_METRIC_NAME = "guard.rejected"
        const val REASON_TAG_LIMIT = 50

        // Phase 3 step 2: a WS price older than this (or from a disconnected socket) is not counted
        // as fresh for the parity comparison. ~5s comfortably exceeds OKX's sub-second ticker cadence.
        const val PRICE_PARITY_MAX_AGE_MS = 5_000L

        // Phase 3 step 3 safety belt: even a FRESH WS price that sits more than this far from the REST
        // price is treated as untrustworthy (bad tick) and rejected in favour of REST. getFreshPrice
        // already covers stale/disconnected; this guards the fresh-but-wrong case. Conservative (0.5%).
        val MAX_TRUSTED_ENTRY_DELTA_BPS: BigDecimal = BigDecimal("50")
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
    suspend fun execute(
        decisions: AiTradeDecisionMap,
        riskContext: RiskContext,
        marketState: MarketState,
    ): List<AiTradeExecutionResult> = coroutineScope {
        log.info { "Executing AI trading decisions for ${decisions.size} symbols" }

        val supportedSymbols = tradingProperties.getCurrenciesList().toSet()

        // P2: market state is loaded once per cycle by BuildPromptUseCase and threaded in here,
        // rather than re-loaded. Available cash comes from that snapshot; entry pricing still uses
        // a FRESH trading.getLastPrice() per symbol in buildPlan, so order pricing is not staler.
        val availableUsd = marketState.account.availableCash

        // S3: one reservation counter per cycle, seeded with the frozen open-position count. Plans
        // execute concurrently below, so the position cap must be enforced per ORDER (atomically),
        // not against the frozen per-cycle count — otherwise N racing plans all pass the same check.
        val slots = ConcurrentSlotLimiter(riskContext.openPositionsCount)

        // Pipeline: Decision -> Plan -> Execution
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
                            executePlan(ready.plan, availableUsd, riskContext, slots)
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
        val restPrice = trading.getLastPrice(instrumentId).getOrNull()
            ?: return PlanResult.Skip(symbol, "No price available")

        // Phase 3 step 2 (parity observer): always log the fresh WS price next to the REST price for
        // evidence — kept on even after step 3 is enabled, so we keep watching the delta.
        logPriceParity(instrumentId, restPrice)

        // Phase 3 step 3 (value-swap, default OFF): when trading.useStreamingEntryPrice is on, size on
        // the fresh WS price instead of REST — but only when connected, fresh, AND within the sanity
        // delta of REST. Any of those failing -> REST fallback, so this is never worse than today.
        val lastPrice = resolveEntryPrice(instrumentId, restPrice)

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

    private suspend fun executePlan(
        plan: OrderPlan,
        availableUsd: BigDecimal,
        riskContext: RiskContext,
        slots: ConcurrentSlotLimiter,
    ): AiTradeExecutionResult {
        // Portfolio-wide gates: kill-switch, daily-loss, and the frozen position-count check.
        when (val d = riskGate.evaluate(riskContext)) {
            is RiskDecision.Allow -> Unit
            is RiskDecision.Block -> {
                recordRiskBlock(d.source.name)
                BusinessEventLogger.orderRejected(plan.symbol, null, d.reason, "RISK_${d.source.name}")
                return createSkippedResult(plan, "RiskGate(${d.source}): ${d.reason}")
            }
        }

        // S3: enforce the position cap PER ORDER, atomically. riskGate's count check above uses the
        // frozen per-cycle count; under concurrency that lets multiple plans pass the same sub-cap
        // check. Reserve a slot here so at most (cap - base) orders proceed this cycle. The slot is
        // released in `finally` on any non-placement exit (skip, rejection, or thrown error).
        val capEnforced = riskGateProperties.enabled
        if (capEnforced && !slots.tryReserve(riskGateProperties.maxConcurrentPositions)) {
            recordRiskBlock(RiskDecision.Source.POSITION_COUNT_CAP.name)
            BusinessEventLogger.orderRejected(
                plan.symbol, null,
                "position cap ${riskGateProperties.maxConcurrentPositions} reached this cycle",
                "RISK_${RiskDecision.Source.POSITION_COUNT_CAP.name}"
            )
            return createSkippedResult(plan, "RiskGate(POSITION_COUNT_CAP): per-cycle cap reached")
        }

        var placed = false
        try {
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
                val result = handleSuccessfulOrder(
                    plan,
                    sizing,
                    clOrdId = "DEMO-$clOrdId",
                    ordId = "DEMO-ORD-${UUID.randomUUID()}",
                    demo = true
                )
                placed = true
                return result
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
                val result = handleSuccessfulOrder(plan, sizing, clOrdId, outcome.ordId)
                placed = true
                result
            } else {
                handleFailedOrder(plan, clOrdId, outcome.message)
            }
        } finally {
            // Release the reserved slot unless an order was actually placed. Covers every
            // non-placement exit, including thrown errors from setLeverage/placeOrder.
            if (capEnforced && !placed) slots.release()
        }
    }

    private fun handleSuccessfulOrder(
        plan: OrderPlan,
        sizing: OrderSizingPolicy.SizingResult,
        clOrdId: String,
        ordId: String?,
        demo: Boolean = false
    ): AiTradeExecutionResult {
        // S6: demo (paper) fills must not mutate production state. Skip cooldown/calibration and
        // idempotency recording so a simulated order doesn't suppress a later real signal or skew
        // confidence calibration. Only real fills update these.
        if (!demo) {
            confidenceCalibrator.recordTrade(plan.symbol)
            plan.signalKey?.let { idempotencyService.recordSignal(it) }
        }

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
            costUsd = sizing.totalUsd,
            demo = demo
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

    private fun recordRiskBlock(source: String) {
        meterRegistry.counter("risk.gate.blocked", "source", source).increment()
    }

    private fun recordGuardRejection(reason: String) {
        val normalized = reason.take(REASON_TAG_LIMIT)
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .lowercase()
        meterRegistry.counter(GUARD_METRIC_NAME, "reason", normalized).increment()
    }

    /**
     * Phase 3 step 3: the entry price actually used for sizing/guardrails. REST unless the streaming
     * flag is on AND [StreamingMarketDataPort.getFreshPrice] yields a connected+fresh price within the
     * sanity delta of REST. Logs a warning (but still falls back) when a fresh WS price is rejected
     * for diverging too far — that signals a misbehaving feed while the flag is live.
     */
    private fun resolveEntryPrice(instrumentId: InstrumentId, restPrice: BigDecimal): BigDecimal {
        if (!tradingProperties.useStreamingEntryPrice) return restPrice
        val wsFresh = streaming.getFreshPrice(instrumentId.value, PRICE_PARITY_MAX_AGE_MS)
        val chosen = selectEntryPrice(wsFresh, restPrice, MAX_TRUSTED_ENTRY_DELTA_BPS)
        if (wsFresh != null && chosen !== wsFresh) {
            log.warn {
                "WS entry price ${instrumentId.value} rejected: deltaBps=${priceDeltaBps(wsFresh, restPrice)} " +
                    "> $MAX_TRUSTED_ENTRY_DELTA_BPS; using REST $restPrice"
            }
        }
        return chosen
    }

    /**
     * Phase 3 step 2: log the fresh WS price next to the REST price actually used for this plan, so
     * we can see the WS-vs-REST delta over a real run before trusting the WS price for entry sizing
     * (step 3). Pure observation — never changes pricing. ws=null means the socket was disconnected
     * or the last tick was older than [PRICE_PARITY_MAX_AGE_MS] (the guarded, safe-to-ignore case).
     */
    private fun logPriceParity(instrumentId: InstrumentId, restPrice: BigDecimal) {
        val wsFresh = streaming.getFreshPrice(instrumentId.value, PRICE_PARITY_MAX_AGE_MS)
        if (wsFresh == null) {
            log.info { "price-parity ${instrumentId.value}: ws=null(stale/disconnected) rest=$restPrice" }
            return
        }
        val deltaBps = priceDeltaBps(wsFresh, restPrice)
        log.info { "price-parity ${instrumentId.value}: ws=$wsFresh rest=$restPrice deltaBps=$deltaBps" }
    }
}

/**
 * Absolute WS-vs-REST price gap in basis points, |ws - rest| / rest * 10_000, scaled to 2 dp.
 * Extracted + pinned because this number IS the Phase-3 step-2 evidence the step-3 value-swap is
 * judged on — a wrong formula would silently justify (or block) trusting the WS price. Returns ZERO
 * when restPrice is zero (no meaningful ratio).
 */
internal fun priceDeltaBps(wsPrice: BigDecimal, restPrice: BigDecimal): BigDecimal {
    if (restPrice.signum() == 0) return BigDecimal.ZERO
    return (wsPrice - restPrice).abs()
        .divide(restPrice, 8, RoundingMode.HALF_UP)
        .multiply(BigDecimal(10_000))
        .setScale(2, RoundingMode.HALF_UP)
}

/**
 * Phase 3 step 3 entry-price choice: the fresh WS price when present AND within [maxDeltaBps] of
 * REST, else REST. Pure + pinned because it picks the number that sizes a real order. Returns one
 * of the two argument instances, so the caller can use reference identity to tell which was chosen.
 */
internal fun selectEntryPrice(
    wsFresh: BigDecimal?,
    restPrice: BigDecimal,
    maxDeltaBps: BigDecimal,
): BigDecimal {
    if (wsFresh == null) return restPrice
    if (priceDeltaBps(wsFresh, restPrice) > maxDeltaBps) return restPrice
    return wsFresh
}
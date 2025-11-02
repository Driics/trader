package ru.driics.aitrade.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.model.AIAction
import ru.driics.aitrade.model.AiTradeDecisionMap
import ru.driics.aitrade.model.AiTradeExecutionResult
import ru.driics.aitrade.model.AiTradeSignalArgs
import ru.driics.aitrade.model.OkxInstrumentInfo
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicLong

@Service
class AiTradeExecutionService(
    private val okxMarketDataService: OkxMarketDataService,
    private val okxTradingService: OkxTradingService,
    private val okxHttpClient: OkxHttpClient,
    private val tradingProperties: TradingProperties
) {
    companion object {
        private val log = KotlinLogging.logger {  }

        private val minConfidence = BigDecimal("0.60")
        private val minLeverage = 5
        private val maxLeverage = 40
        private val defaultLeverage = 10
    }
    private val mapper = jacksonObjectMapper()
    private val clCounter = AtomicLong(0)

    private fun makeClOrdId(symbol: String): String {
        val sym = symbol.filter { it.isLetterOrDigit() }.uppercase()
        val head = ("AI" + sym).take(8)
        val ts36 = System.currentTimeMillis().toString(36).uppercase()
        val c36 = (clCounter.incrementAndGet() and 0xFFFF).toString(36).uppercase()
        val raw = head + ts36 + c36
        return if (raw.length <= 32) raw else raw.takeLast(32)
    }

    /**
     * Execute AI decisions (JSON string) across supported symbols.
     * - Skips "hold"
     * - Sizes from quantity (coin units) if provided; otherwise from risk_usd and stop_loss gap
     * - Converts to contract size using ctVal/ctValCcy and entry price
     * - Rounds sz to lotSz multiple and >= minSz
     * - Sets leverage (cross) and places a MARKET order with TP/SL attached
     */
    suspend fun execute(aiJson: String): List<AiTradeExecutionResult> = coroutineScope {
        val supported = tradingProperties.getCurrenciesList().map { it.uppercase(Locale.ROOT) }.toSet()

        val parsed: AiTradeDecisionMap = try {
            mapper.readValue(aiJson)
        } catch (e: Exception) {
            log.error(e) { "Failed to parse AI JSON" }
            return@coroutineScope listOf(
                AiTradeExecutionResult("*", AIAction.SKIPPED, "Invalid AI JSON: ${e.message}")
            )
        }

        var remainingCashUsd = okxMarketDataService.fetchAccountInfo()
            .availableCash.max(BigDecimal.ZERO)

        val planResults = parsed.values.map { env ->
            async {
                buildPlan(env.args, supported)
            }
        }.awaitAll()

        val results = mutableListOf<AiTradeExecutionResult>()
        val taker = tradingProperties.takerFeePct
        val buffer = tradingProperties.marginBufferPct

        for (res in planResults) {
            when (res) {
                is PlanResult.Skip -> {
                    results += AiTradeExecutionResult(
                        symbol = res.symbol,
                        action = AIAction.SKIPPED,
                        message = res.reason
                    )
                }

                is PlanResult.Ready -> {
                    val plan = res.plan

                    val perContractCost = perContractCashCost(plan.valuePerContractUsd, plan.leverage, taker, buffer)
                    val maxAffordable = affordableContracts(
                        availableUsd = remainingCashUsd,
                        perContractCostUsd = perContractCost,
                        lot = plan.lotSz,
                        min = plan.minSz
                    )

                    if (maxAffordable <= BigDecimal.ZERO) {
                        results += AiTradeExecutionResult(
                            symbol = plan.symbol,
                            action = AIAction.SKIPPED,
                            message = "Insufficient margin: avail=$remainingCashUsd USD, perContract=${perContractCost.setScale(6, RoundingMode.HALF_UP)}"
                        )
                        continue
                    }

                    val finalContracts =
                        if (plan.roundedContracts > maxAffordable) {
                            log.info(
                                "Capped {} from {} to {} contracts (lev={}, fee={}, buffer={})",
                                plan.instId,
                                plan.roundedContracts.stripTrailingZeros(),
                                maxAffordable.stripTrailingZeros(),
                                plan.leverage, taker, buffer
                            )
                            maxAffordable
                        } else plan.roundedContracts

                    val totalCash = finalContracts.multiply(perContractCost)

                    val levOk = withContext(Dispatchers.IO) {
                        okxTradingService.setCrossLeverage(plan.instId, plan.leverage)
                    }

                    if (!levOk) {
                        results += AiTradeExecutionResult(
                            symbol = plan.symbol,
                            action = AIAction.SKIPPED,
                            message = "Failed to set leverage ${plan.leverage}",
                            instId = plan.instId
                        )
                        continue
                    }

                    val clId = makeClOrdId(plan.symbol)
                    val (ok, ordId) = withContext(Dispatchers.IO) {
                        okxTradingService.placeMarketOrderWithTpSl(
                            instId = plan.instId,
                            side = plan.side,
                            szContracts = finalContracts,
                            tpPx = plan.tpPx,
                            slPx = plan.slPx,
                            tickSz = plan.tickSz,
                            clOrdId = clId
                        )
                    }

                    if (ok) {
                        remainingCashUsd = (remainingCashUsd - totalCash).max(BigDecimal.ZERO)
                        results += AiTradeExecutionResult(
                            symbol = plan.symbol,
                            action = AIAction.PLACED,
                            message = "Order accepted (used ≈ ${totalCash.setScale(6, RoundingMode.HALF_UP)} USD)",
                            instId = plan.instId,
                            clOrdId = clId,
                            ordId = ordId,
                            requestedContracts = plan.requestedContracts,
                            placedContracts = finalContracts
                        )
                    } else {
                        results += AiTradeExecutionResult(
                            symbol = plan.symbol,
                            action = AIAction.SKIPPED,
                            message = "Order rejected by venue",
                            instId = plan.instId,
                            clOrdId = clId,
                            requestedContracts = plan.requestedContracts
                        )
                    }
                }
            }
        }

        results
    }

    private fun roundContracts(raw: BigDecimal, lot: BigDecimal, min: BigDecimal): BigDecimal {
        if (raw <= BigDecimal.ZERO) return BigDecimal.ZERO
        // floor to lot multiple
        val steps = raw.divide(lot, 0, RoundingMode.FLOOR)
        val floored = steps.multiply(lot)
        return if (floored < min) BigDecimal.ZERO else floored.stripTrailingZeros()
    }

    private sealed class PlanResult {
        data class Skip(val symbol: String, val reason: String) : PlanResult()
        data class Ready(val plan: OrderPlan) : PlanResult()
    }

    private data class OrderPlan(
        val symbol: String,
        val instId: String,
        val side: String, // "buy" | "sell"
        val leverage: Int,
        val requestedContracts: BigDecimal,
        val roundedContracts: BigDecimal,
        val minSz: BigDecimal,
        val lotSz: BigDecimal,
        val tickSz: BigDecimal,
        val tpPx: BigDecimal?,
        val slPx: BigDecimal?,
        val valuePerContractUsd: BigDecimal
    )

    private suspend fun buildPlan(
        args: AiTradeSignalArgs,
        supported: Set<String>
    ): PlanResult = coroutineScope {
        val symbol = args.coin.uppercase(Locale.ROOT)
        if (symbol !in supported) return@coroutineScope PlanResult.Skip(symbol, "Symbol not in configured list")

        val signal = args.signal.lowercase(Locale.ROOT)
        if (signal == "hold") return@coroutineScope PlanResult.Skip(symbol, "Signal hold")
        if (signal != "buy" && signal != "sell") return@coroutineScope PlanResult.Skip(symbol, "Unsupported signal: ${args.signal}")

        val confidence = args.confidence ?: BigDecimal.ZERO
        if (confidence < minConfidence) return@coroutineScope PlanResult.Skip(symbol, "Confidence $confidence below threshold $minConfidence")

        val instId = "${symbol}-USDT-SWAP"

        // Fetch instrument + ticker concurrently
        val instDeferred = async(Dispatchers.IO) { okxTradingService.loadInstrument(instId) }
        val tickDeferred = async(Dispatchers.IO) { okxHttpClient.fetchTicker(instId) }

        val inst = instDeferred.await() ?: return@coroutineScope PlanResult.Skip(symbol, "No instrument info for $instId")
        val ticker = tickDeferred.await()
        val entryPx = ticker?.lastPrice?.toBigDecimalOrNull()
            ?: ticker?.askPrice?.toBigDecimalOrNull()
            ?: ticker?.bidPrice?.toBigDecimalOrNull()
            ?: return@coroutineScope PlanResult.Skip(symbol, "No price for $instId")

        val sl = args.stopLoss
        val tp = args.profitTarget

        // Quantity in coins (prefer explicit, else risk-based)
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
        if (coinQty <= BigDecimal.ZERO) return@coroutineScope PlanResult.Skip(symbol, "Zero/unknown quantity (no risk sizing possible)")

        // Convert to contracts + value per contract
        val (contractsRaw, minSz, lotSz, valuePerContractUsd) = toContractsAndValue(coinQty, entryPx, inst)
        if (contractsRaw <= BigDecimal.ZERO || valuePerContractUsd <= BigDecimal.ZERO) {
            return@coroutineScope PlanResult.Skip(symbol, "Computed contracts/value <= 0")
        }

        // Round upfront
        val rounded = roundContracts(contractsRaw, lotSz, minSz)
        if (rounded <= BigDecimal.ZERO) return@coroutineScope PlanResult.Skip(symbol, "Rounded contracts below minSz")

        val lev = (args.leverage ?: defaultLeverage).coerceIn(minLeverage, maxLeverage)
        val tick = inst.tickSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("0.01")
        val side = if (signal == "buy") "buy" else "sell"

        PlanResult.Ready(
            OrderPlan(
                symbol = symbol,
                instId = instId,
                side = side,
                leverage = lev,
                requestedContracts = contractsRaw,
                roundedContracts = rounded,
                minSz = minSz,
                lotSz = lotSz,
                tickSz = tick,
                tpPx = tp,
                slPx = sl,
                valuePerContractUsd = valuePerContractUsd
            )
        )
    }

    // ---------- Math & helpers ----------

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun toContractsAndValue(
        coinQty: BigDecimal,
        entryPx: BigDecimal,
        inst: OkxInstrumentInfo
    ): Quadruple<BigDecimal, BigDecimal, BigDecimal, BigDecimal> {
        val lot = inst.lotSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
        val min = inst.minSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: lot
        val ctVal = inst.ctVal?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return Quadruple(BigDecimal.ZERO, min, lot, BigDecimal.ZERO)
        val ccy = (inst.ctValCcy ?: "").uppercase(Locale.ROOT)

        val contracts =
            if (ccy == "USDT" || ccy == "USD" || ccy == "USB")
                coinQty.multiply(entryPx).divide(ctVal, 8, RoundingMode.HALF_UP)
            else
                coinQty.divide(ctVal, 8, RoundingMode.HALF_UP)

        val valuePerContractUsd =
            if (ccy == "USDT" || ccy == "USD" || ccy == "USB")
                ctVal
            else
                ctVal.multiply(entryPx)

        return Quadruple(contracts, min, lot, valuePerContractUsd)
    }

    private fun perContractCashCost(
        valuePerContractUsd: BigDecimal,
        leverage: Int,
        takerFeePct: BigDecimal,
        bufferPct: BigDecimal
    ): BigDecimal {
        val invLev = BigDecimal.ONE.divide(BigDecimal(leverage), 8, RoundingMode.HALF_UP)
        val overhead = invLev + takerFeePct + bufferPct
        return valuePerContractUsd.multiply(overhead)
    }

    private fun affordableContracts(
        availableUsd: BigDecimal,
        perContractCostUsd: BigDecimal,
        lot: BigDecimal,
        min: BigDecimal
    ): BigDecimal {
        if (availableUsd <= BigDecimal.ZERO || perContractCostUsd <= BigDecimal.ZERO) return BigDecimal.ZERO
        val rawMax = availableUsd.divide(perContractCostUsd, 8, RoundingMode.FLOOR)
        val steps = rawMax.divide(lot, 0, RoundingMode.FLOOR)
        val floored = steps.multiply(lot)
        return if (floored < min) BigDecimal.ZERO else floored.stripTrailingZeros()
    }



    private fun BigDecimal.max(other: BigDecimal) = if (this >= other) this else other
}
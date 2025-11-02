package ru.driics.aitrade.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.model.AiTradeDecisionMap
import ru.driics.aitrade.model.AiTradeExecutionResult
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
    fun execute(aiJson: String): List<AiTradeExecutionResult> {
        val now = Instant.now().toEpochMilli()
        val supported = tradingProperties.getCurrenciesList().map { it.uppercase(Locale.ROOT) }.toSet()

        val parsed: AiTradeDecisionMap = try {
            mapper.readValue(aiJson)
        } catch (e: Exception) {
            log.error("Failed to parse AI JSON", e)
            return listOf(
                AiTradeExecutionResult("*", "skipped", "Invalid AI JSON: ${e.message}")
            )
        }

        val results = mutableListOf<AiTradeExecutionResult>()

        for ((_, env) in parsed) {
            val args = env.args
            val symbol = args.coin.uppercase(Locale.ROOT)

            if (!supported.contains(symbol)) {
                results += AiTradeExecutionResult(symbol, "skipped", "Symbol not in configured list")
                continue
            }
            val signal = args.signal.lowercase(Locale.ROOT)
            if (signal == "hold") {
                results += AiTradeExecutionResult(symbol, "skipped", "Signal hold")
                continue
            }
            if (signal != "buy" && signal != "sell") {
                results += AiTradeExecutionResult(symbol, "skipped", "Unsupported signal: ${args.signal}")
                continue
            }

            val conf = args.confidence ?: BigDecimal.ZERO
            if (conf < minConfidence) {
                results += AiTradeExecutionResult(symbol, "skipped", "Confidence ${conf} below threshold $minConfidence")
                continue
            }

            val instId = "${symbol}-USDT-SWAP"
            val inst = okxTradingService.loadInstrument(instId)
            if (inst == null) {
                results += AiTradeExecutionResult(symbol, "skipped", "No instrument info for $instId")
                continue
            }

            // Entry price from ticker
            val ticker = okxHttpClient.fetchTicker(instId)
            val entryPx = ticker?.lastPrice?.toBigDecimalOrNull()
                ?: ticker?.askPrice?.toBigDecimalOrNull()
                ?: ticker?.bidPrice?.toBigDecimalOrNull()
            if (entryPx == null || entryPx <= BigDecimal.ZERO) {
                results += AiTradeExecutionResult(symbol, "skipped", "No price for $instId")
                continue
            }

            val stopLoss = args.stopLoss
            val profitTarget = args.profitTarget

            if (signal == "buy") {
                if (profitTarget != null && profitTarget <= entryPx) {
                    results += AiTradeExecutionResult(symbol, "skipped", "Invalid profit_target for buy: $profitTarget <= $entryPx")
                    continue
                }
                if (stopLoss != null && stopLoss >= entryPx) {
                    results += AiTradeExecutionResult(symbol, "skipped", "Invalid stop_loss for buy: $stopLoss >= $entryPx")
                    continue
                }
            } else if (signal == "sell") {
                if (profitTarget != null && profitTarget >= entryPx) {
                    results += AiTradeExecutionResult(symbol, "skipped", "Invalid profit_target for sell: $profitTarget >= $entryPx")
                    continue
                }
                if (stopLoss != null && stopLoss <= entryPx) {
                    results += AiTradeExecutionResult(symbol, "skipped", "Invalid stop_loss for sell: $stopLoss <= $entryPx")
                    continue
                }
            }

            // Quantity in coin units: prefer provided quantity if > 0; else compute from risk_usd / price gap
            val coinQty = when {
                (args.quantity ?: BigDecimal.ZERO) > BigDecimal.ZERO -> args.quantity!!
                args.riskUsd != null && stopLoss != null && stopLoss > BigDecimal.ZERO -> {
                    val riskPerUnit = (entryPx - stopLoss).abs()
                    val minRisk = entryPx.multiply(BigDecimal("0.005"))
                    if (riskPerUnit < minRisk) {
                        log.warn("Risk per unit too small for {}: {} < {}", symbol, riskPerUnit, minRisk)
                        BigDecimal.ZERO
                    } else {
                        args.riskUsd.divide(riskPerUnit, 8, RoundingMode.HALF_UP)
                    }
                }
                else -> BigDecimal.ZERO
            }

            if (coinQty <= BigDecimal.ZERO) {
                results += AiTradeExecutionResult(symbol, "skipped", "Zero/unknown quantity (no risk sizing possible)")
                continue
            }

            // Convert coinQty => contracts (sz) using ctVal/ctValCcy/entryPx
            val (contractsRaw, minSz, lotSz) = toContracts(coinQty, entryPx, inst)
            if (contractsRaw <= BigDecimal.ZERO) {
                results += AiTradeExecutionResult(symbol, "skipped", "Computed contracts <= 0")
                continue
            }

            // Round contracts to lotSz multiple and minSz
            val szRounded = roundContracts(contractsRaw, lotSz, minSz)
            if (szRounded <= BigDecimal.ZERO) {
                results += AiTradeExecutionResult(symbol, "skipped", "Rounded contracts below minSz")
                continue
            }

            // Clamp/choose leverage
            val lev = (args.leverage ?: defaultLeverage).coerceIn(minLeverage, maxLeverage)

            // Set leverage first (OKX cross for SWAP)
            val levOk = okxTradingService.setCrossLeverage(instId, lev)
            if (!levOk) {
                results += AiTradeExecutionResult(symbol, "skipped", "Failed to set leverage $lev")
                continue
            }

            // Tick size for TP/SL quantization
            val tick = inst.tickSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("0.01")

            // Place market order and attach TP/SL
            val clId = makeClOrdId(symbol)
            val side = if (signal == "buy") "buy" else "sell"

            val (ok, ordId) = okxTradingService.placeMarketOrderWithTpSl(
                instId = instId,
                side = side,
                szContracts = szRounded,
                tpPx = profitTarget,
                slPx = stopLoss,
                tickSz = tick,
                clOrdId = clId
            )

            if (ok) {
                results += AiTradeExecutionResult(
                    symbol = symbol,
                    action = "placed",
                    message = "Order accepted",
                    instId = instId,
                    clOrdId = clId,
                    ordId = ordId,
                    requestedContracts = contractsRaw,
                    placedContracts = szRounded
                )
            } else {
                results += AiTradeExecutionResult(
                    symbol = symbol,
                    action = "skipped",
                    message = "Order rejected",
                    instId = instId,
                    clOrdId = clId,
                    requestedContracts = contractsRaw
                )
            }
        }

        return results
    }

    private fun toContracts(
        coinQty: BigDecimal,
        entryPx: BigDecimal,
        inst: OkxInstrumentInfo
    ): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val lot = inst.lotSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
        val min = inst.minSz?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: lot

        val ctVal = inst.ctVal?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return Triple(BigDecimal.ZERO, min, lot)
        val ctValCcy = (inst.ctValCcy ?: "").uppercase(Locale.ROOT)

        val contracts = when {
            // For USDT/USB/ USD linear contracts, ctVal is in quote currency value:
            ctValCcy == "USDT" || ctValCcy == "USD" || ctValCcy == "USB" ->
                coinQty.multiply(entryPx).divide(ctVal, 8, RoundingMode.HALF_UP)
            // For coin-margined, ctVal is base coin amount per contract:
            else -> coinQty.divide(ctVal, 8, RoundingMode.HALF_UP)
        }
        return Triple(contracts, min, lot)
    }

    private fun roundContracts(raw: BigDecimal, lot: BigDecimal, min: BigDecimal): BigDecimal {
        if (raw <= BigDecimal.ZERO) return BigDecimal.ZERO
        // floor to lot multiple
        val steps = raw.divide(lot, 0, RoundingMode.FLOOR)
        val floored = steps.multiply(lot)
        return if (floored < min) BigDecimal.ZERO else floored.stripTrailingZeros()
    }
}
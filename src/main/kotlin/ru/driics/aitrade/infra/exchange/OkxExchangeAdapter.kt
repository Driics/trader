package ru.driics.aitrade.infra.exchange

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.*
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Service
import ru.driics.aitrade.common.traced
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PlaceOrderOutcome
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.services.IndicatorCalculator
import ru.driics.aitrade.domain.types.InstrumentId
import ru.driics.aitrade.domain.types.Symbol
import ru.driics.aitrade.domain.types.TradeResult
import ru.driics.aitrade.domain.util.quantize
import ru.driics.aitrade.service.OkxRestClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service
class OkxExchangeAdapter(
    private val rest: OkxRestClient,
    private val tradingProperties: TradingProperties,
    private val tracer: Tracer
) : MarketDataPort, TradingPort {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val invocationCount = AtomicLong(0L)
    private val initialAccountEquity = AtomicReference<BigDecimal?>(null)

    private val instrumentCache = Caffeine.newBuilder()
        .expireAfterWrite(tradingProperties.instrumentCacheTtl)
        .maximumSize(100)
        .build<String, OkxInstrumentInfo>()

    private val candles4HCache = Candles4HCache()

    override suspend fun loadMarketState(symbols: List<Symbol>): MarketState = withContext(Dispatchers.IO) {
        val count = invocationCount.incrementAndGet()
        log.debug { "Loading market state for ${symbols.size} symbols (invocation #$count)" }

        val semaphore = Semaphore(tradingProperties.maxConcurrentSymbols)

        // Convert Symbol to String for internal processing
        val symbolStrings = symbols.map { it.value }

        val currencies = symbolStrings.associateWith { symbolStr ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    withTimeout(5000) {
                        runCatching {
                            fetchCurrencyData(symbolStr)
                        }.getOrElse { e ->
                            log.error(e) { "Failed to fetch data for $symbolStr" }
                            createEmptyCurrencyData(symbolStr)
                        }
                    }
                }
            }
        }.mapValues { it.value.await() }


        val positions = async(Dispatchers.IO) {
            tracer.traced("fetch_positions") { fetchPositions() }
        }.await()
        val account = async(Dispatchers.IO) {
            tracer.traced("fetch_account") { fetchAccountInfo(positions) }
        }

        MarketState(
            timestamp = System.currentTimeMillis(),
            minutesSinceStart = (System.currentTimeMillis() - sessionStartTime.get()) / 60000,
            invocationCount = count,
            currencies = currencies,
            account = account.await(),
            positions = positions
        )
    }

    suspend fun fetchCurrencyData(symbol: String): CurrencyMarketData = coroutineScope {
        val instId = "${symbol}-USDT-SWAP"

        // Fetch current ticker
        val ticker = rest.fetchTicker(instId)
        val currentPrice = ticker?.bidPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        // Fetch 3-minute candles
        val candles = async { rest.fetchCandles(instId, "3m", 100) }.await()
        val prices = async { candles.mapNotNull { it.close.toBigDecimalOrNull() } }.await()

        // Calculate current indicators
        val ema20 = async(Dispatchers.Default) { IndicatorCalculator.calculateEMA(prices, 20) }
        val macd = async(Dispatchers.Default) { IndicatorCalculator.calculateMACD(prices) }
        val rsi7 = async(Dispatchers.Default) { IndicatorCalculator.calculateRSI(prices, 7) }

        // Calculate progressive indicators for intraday series
        val intradayEma20 = async(Dispatchers.Default) { IndicatorCalculator.calculateProgressiveEMA(prices, 20) }
        val intradayMacd = async(Dispatchers.Default) { IndicatorCalculator.calculateProgressiveMACD(prices) }
        val intradayRsi7 = async(Dispatchers.Default) { IndicatorCalculator.calculateProgressiveRSI(prices, 7) }
        val intradayRsi14 = async(Dispatchers.Default) { IndicatorCalculator.calculateProgressiveRSI(prices, 14) }

        // Fetch 4-hour candles for longer-term context
        val candles4HDeferred = async { fetch4HCandlesWithCache(symbol, instId) }
        val candles4h = candles4HDeferred.await()

        val prices4h = candles4h.mapNotNull { it.close.toBigDecimalOrNull() }

        val ema20_4h = async(Dispatchers.Default) { IndicatorCalculator.calculateEMA(prices4h, 20) }
        val ema50_4h = async(Dispatchers.Default) { IndicatorCalculator.calculateEMA(prices4h, 50) }
        val atr3_4h = async(Dispatchers.Default) { IndicatorCalculator.calculateATR(candles4h, 3) }
        val atr14_4h = async(Dispatchers.Default) { IndicatorCalculator.calculateATR(candles4h, 14) }

        val volume4h = candles4h.lastOrNull()?.volume?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val avgVolume4h = calculateAverageVolume(candles4h, last = 20)

        val macd4h = IndicatorCalculator.calculateProgressiveMACD(prices4h)
        val rsi14_4h = IndicatorCalculator.calculateProgressiveRSI(prices4h, 14)


        // Ensure exactly 10 values in 4H series (pad with zeros if needed)
        val macd4hPadded = macd4h.takeLast(10).let { list ->
            if (list.size < 10) {
                List(10 - list.size) { BigDecimal.ZERO } + list
            } else {
                list
            }
        }

        val rsi14_4hPadded = rsi14_4h.takeLast(10).let { list ->
            if (list.size < 10) {
                List(10 - list.size) { BigDecimal.ZERO } + list
            } else {
                list
            }
        }

        // Fetch funding rate and open interest
        val fundingRateDeferred = async { rest.fetchFundingRate(instId) }
        val openInterestDeferred = async { rest.fetchOpenInterest(instId) }

        val fundingRate = fundingRateDeferred.await()
        val openInterest = openInterestDeferred.await()

        return@coroutineScope CurrencyMarketData(
            symbol = symbol,
            currentPrice = currentPrice,
            currentEma20 = ema20.await(),
            currentMacd = macd.await(),
            currentRsi7 = rsi7.await(),
            openInterest = openInterest,
            fundingRate = fundingRate,
            intradayPrices = prices.takeLast(10),
            intradayEma20 = intradayEma20.await().takeLast(10),
            intradayMacd = intradayMacd.await().takeLast(10),
            intradayRsi7 = intradayRsi7.await().takeLast(10),
            intradayRsi14 = intradayRsi14.await().takeLast(10),
            ema20_4h = ema20_4h.await(),
            ema50_4h = ema50_4h.await(),
            atr3_4h = atr3_4h.await(),
            atr14_4h = atr14_4h.await(),
            volume4h = volume4h,
            avgVolume4h = avgVolume4h,
            macd4h = macd4hPadded,
            rsi14_4h = rsi14_4hPadded
        )
    }

    private fun createEmptyCurrencyData(symbol: String) = CurrencyMarketData(
        symbol = symbol,
        currentPrice = BigDecimal.ZERO,
        currentEma20 = BigDecimal.ZERO,
        currentMacd = BigDecimal.ZERO,
        currentRsi7 = BigDecimal.ZERO
    )

    private suspend fun fetchAccountInfo(prefetchedPositions: List<Position>? = null): AccountInfo {
        val acc = rest.fetchAccount()
        val totalEq = acc.totalEquity.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val avail = acc.availableBalance.toBigDecimalOrNull() ?: BigDecimal.ZERO

        val baseline = initialAccountEquity.get() ?: run {
            initialAccountEquity.set(totalEq)
            totalEq
        }

        val totalReturnPct = if (baseline > BigDecimal.ZERO) {
            totalEq.subtract(baseline)
                .divide(baseline, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        } else {
            BigDecimal.ZERO
        }

        val positions = prefetchedPositions ?: fetchPositions()
        val availableCash = if (positions.isEmpty() && avail == BigDecimal.ZERO && totalEq > BigDecimal.ZERO) {
            totalEq
        } else {
            avail
        }

        return AccountInfo(
            totalReturn = totalReturnPct,
            availableCash = availableCash,
            accountValue = totalEq,
            sharpeRatio = null
        )
    }

    private suspend fun fetchPositions(): List<Position> {
        val positions = rest.fetchOpenPositions()
        return positions.mapNotNull { pos ->
            try {
                Position(
                    symbol = pos.instrumentId.substringBefore("-"),
                    quantity = pos.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    entryPrice = pos.averagePrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    currentPrice = pos.markPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    liquidationPrice = pos.liquidationPrice.toBigDecimalOrNull(),
                    unrealizedPnl = pos.unrealizedPnl.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    leverage = pos.leverage.toIntOrNull()
                )
            } catch (e: Exception) {
                log.warn(e) { "Failed to parse position: ${pos.instrumentId}" }
                null
            }
        }
    }

    /**
     * Fetch 4H candles with caching to avoid recalculations.
     * Checks if data has changed since last fetch.
     */
    private suspend fun fetch4HCandlesWithCache(symbol: String, instId: String): List<OkxCandleResponse> {
        val newCandles = rest.fetchCandles(instId, "4H", 200)

        if (newCandles.isEmpty()) {
            log.warn { "No 4H candles returned for $symbol" }
            return emptyList()
        }

        val newHash = Candles4HCache.generateHash(newCandles)
        val lastTimestamp = newCandles.lastOrNull()?.timestamp?.toLongOrNull() ?: Long.MAX_VALUE

        val cached = candles4HCache.get(symbol)

        // If hash matches, data hasn't changed - return cached candles
        if (cached != null && cached.dataHash == newHash) {
            log.debug { "4H data unchanged for $symbol, using cached version" }
            return cached.candles
        }

        // Data changed or no cache - update cache
        log.debug { "4H data updated for $symbol (hash: $newHash)" }
        candles4HCache.put(
            symbol,
            Cached4HData(
                candles = newCandles,
                lastCandleTimestamp = lastTimestamp,
                dataHash = newHash
            )
        )

        return newCandles
    }

    private fun calculateAverageVolume(candles: List<OkxCandleResponse>, last: Int? = null): BigDecimal {
        if (candles.isEmpty()) return BigDecimal.ZERO
        val candlesToUse = if (last != null && candles.size > last) {
            candles.takeLast(last)
        } else {
            candles
        }
        val volumes = candlesToUse.mapNotNull { it.volume.toBigDecimalOrNull() }
        return if (volumes.isNotEmpty()) {
            volumes.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(volumes.size), 10, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    }

    override suspend fun loadInstrument(instrumentId: InstrumentId): TradeResult<OkxInstrumentInfo> {
        val instIdStr = instrumentId.value
        return try {
            val cached = instrumentCache.getIfPresent(instIdStr)
            if (cached != null) {
                return TradeResult.Success(cached)
            }

            val info = rest.getSwapInstrument(instIdStr)
            if (info != null) {
                instrumentCache.put(instIdStr, info)
            }
            if (cached != null)
                return TradeResult.Success(cached)

            val instrumentInfo = rest.getSwapInstrument(instIdStr)
                ?: return TradeResult.Failure.ApiError(
                    code = "INSTRUMENT_NOT_FOUND",
                    message = "Instrument $instIdStr not found"
                )

            instrumentCache.put(instIdStr, instrumentInfo)
            TradeResult.Success(instrumentInfo)
        } catch (e: ClientRequestException) {
            TradeResult.Failure.NetworkError(
                message = "HTTP ${e.response.status.value} fetching instrument $instIdStr",
                cause = e
            )
        } catch (e: Exception) {
            TradeResult.Failure.ApiError(
                code = "UNKNOWN_ERROR",
                message = "Error loading instrument $instIdStr: ${e.message}",
                cause = e
            )
        }
    }

    override suspend fun getLastPrice(instrumentId: InstrumentId): TradeResult<BigDecimal?> {
        val instIdStr = instrumentId.value
        return try {
            val t = rest.fetchTicker(instIdStr)
            val lastPrice = t?.lastPrice?.toBigDecimalOrNull()
                ?: t?.askPrice?.toBigDecimalOrNull()
                ?: t?.bidPrice?.toBigDecimalOrNull()

            TradeResult.Success(lastPrice)
        } catch (e: Exception) {
            TradeResult.Failure.ApiError(
                code = "UNKOWN_ERROR",
                message = "Error loading instrument $instIdStr: ${e.message}",
                cause = e
            )
        }
    }

    override suspend fun setLeverage(
        instrumentId: InstrumentId,
        leverage: Int,
        marginMode: MarginMode
    ): TradeResult<Boolean> {
        val instIdStr = instrumentId.value
        return try {
            val success = rest.setLeverage(
                instId = instIdStr,
                leverage,
                marginMode
            )

            TradeResult.Success(success)
        } catch (e: Exception) {
            TradeResult.Failure.ApiError(
                code = "UNKNOWN_ERROR",
                message = "Failed to set leverage $leverage for $instIdStr in ${marginMode.asOkxApiValue} mode",
                cause = e
            )
        }
    }

    override suspend fun placeMarketOrderWithTpSl(
        instrumentId: InstrumentId,
        side: String,
        contracts: BigDecimal,
        tp: BigDecimal?,
        sl: BigDecimal?,
        tickSz: BigDecimal,
        clOrdId: String,
        tag: String?,
        marginMode: MarginMode
    ): TradeResult<PlaceOrderOutcome> {
        val instIdStr = instrumentId.value
        return try {
            val tpStr = tp?.quantize(tickSz)?.toPlainString()
            val slStr = sl?.quantize(tickSz)?.toPlainString()

            val res = rest.placeMarketOrderWithAttach(
                instId = instIdStr,
                side = side,
                tdMode = marginMode.asOkxApiValue,
                szContracts = contracts.stripTrailingZeros().toPlainString(),
                tpPx = tpStr,
                slPx = slStr,
                posSide = null,
                clOrdId = clOrdId,
                tag = tag
            )

            when {
                res == null -> TradeResult.Failure.ApiError(
                    code = "ORDER_REJECTED",
                    message = "Order placement failed"
                )

                res.sCode == "0" -> TradeResult.Success(
                    PlaceOrderOutcome(
                        ok = true,
                        ordId = res.ordId,
                        message = "Order placed successfully"
                    )
                )

                else -> TradeResult.Failure.ApiError(
                    code = res.sCode ?: "UNKNOWN",
                    message = res.sMsg ?: "Order rejected"
                )
            }
        } catch (e: Exception) {
            TradeResult.Failure.NetworkError(
                message = "Failed to place order: ${e.message}",
                cause = e
            )
        }
    }
}
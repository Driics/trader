package ru.driics.aitrade.infra.exchange

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.infra.cache.IndicatorCache
import ru.driics.aitrade.infra.cache.SmartCacheStrategy
import ru.driics.aitrade.infra.cache.getTyped
import io.ktor.client.plugins.*
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Service
import ru.driics.aitrade.common.traced
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.*
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PlaceOrderOutcome
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.types.InstrumentId
import ru.driics.aitrade.domain.types.Symbol
import ru.driics.aitrade.domain.types.TradeResult
import ru.driics.aitrade.domain.util.quantize
import ru.driics.aitrade.service.OkxRestClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service
class OkxExchangeAdapter(
    private val rest: OkxRestClient,
    private val tradingProperties: TradingProperties,
    private val tracer: Tracer,
    private val smartCache: SmartCacheStrategy,
    private val indicatorCache: IndicatorCache,
    private val clock: Clock
) : MarketDataPort, TradingPort {
    companion object {
        private val log = KotlinLogging.logger { }
        
        // Constants for better maintainability
        private const val CANDLE_TIMEFRAME_3M = "3m"
        private const val CANDLE_TIMEFRAME_4H = "4H"
        private const val CANDLE_COUNT_3M = 100
        private const val CANDLE_COUNT_4H = 200
        private const val INTRADAY_SERIES_SIZE = 10
        private const val EMA_PERIOD_20 = 20
        private const val EMA_PERIOD_50 = 50
        private const val RSI_PERIOD_7 = 7
        private const val RSI_PERIOD_14 = 14
        private const val ATR_PERIOD_3 = 3
        private const val ATR_PERIOD_14 = 14
        private const val VOLUME_AVG_PERIOD = 20
        private const val SYMBOL_FETCH_TIMEOUT_MS = 5000L
        private const val MILLIS_PER_MINUTE = 60000L
        private const val VOLUME_CALCULATION_SCALE = 10
        private const val RETURN_CALCULATION_SCALE = 6
    }

    private val sessionStartTime = AtomicLong(clock.millis())
    private val invocationCount = AtomicLong(0L)
    private val initialAccountEquity = AtomicReference<BigDecimal?>(null)

    private val candles4HCache = Candles4HCache()

    override suspend fun loadMarketState(symbols: List<Symbol>): MarketState = withContext(Dispatchers.IO) {
        val count = invocationCount.incrementAndGet()
        log.debug { "Loading market state for ${symbols.size} symbols (invocation #$count)" }

        // Use Flow internally for streaming, then collect into final result
        val symbolStrings = symbols.map { it.value }
        val currenciesFlow = streamCurrencyData(symbolStrings)
        
        // Collect all currency data into a map
        val currencies = currenciesFlow.toList().associateBy({ it.symbol }, { it })

        // Fetch positions and account info in parallel
        val positions = async(Dispatchers.IO) {
            tracer.traced("fetch_positions") { fetchPositions() }
        }
        val account = async(Dispatchers.IO) {
            tracer.traced("fetch_account") { fetchAccountInfo(positions.await()) }
        }

        MarketState(
            timestamp = clock.millis(),
            minutesSinceStart = (clock.millis() - sessionStartTime.get()) / MILLIS_PER_MINUTE,
            invocationCount = count,
            currencies = currencies,
            account = account.await(),
            positions = positions.await()
        )
    }

    /**
     * Stream currency data as it becomes available.
     * Uses Flow for reactive processing and better backpressure handling.
     * Emits data as soon as each symbol's data is fetched, allowing for progressive processing.
     */
    fun streamCurrencyData(symbols: List<String>): Flow<CurrencyMarketData> = flow {
        val semaphore = Semaphore(tradingProperties.maxConcurrentSymbols)
        
        symbols.asFlow().collect { symbolStr ->
            semaphore.withPermit {
                try {
                    withTimeout(SYMBOL_FETCH_TIMEOUT_MS) {
                        emit(fetchCurrencyData(symbolStr))
                    }
                } catch (e: Exception) {
                    log.error(e) { "Failed to fetch data for $symbolStr" }
                    emit(createEmptyCurrencyData(symbolStr))
                }
            }
        }
    }.buffer(capacity = tradingProperties.maxConcurrentSymbols)

    suspend fun fetchCurrencyData(symbol: String): CurrencyMarketData = coroutineScope {
        val instId = "${symbol}-USDT-SWAP"

        // Fetch ticker and 3-minute candles in parallel
        val tickerDeferred = async { rest.fetchTicker(instId) }
        val candles3mDeferred = async { rest.fetchCandles(instId, CANDLE_TIMEFRAME_3M, CANDLE_COUNT_3M) }
        val candles4hDeferred = async { fetch4HCandlesWithCache(symbol, instId) }
        val fundingRateDeferred = async { rest.fetchFundingRate(instId) }
        val openInterestDeferred = async { rest.fetchOpenInterest(instId) }

        // Wait for initial data
        val ticker = tickerDeferred.await()
        val currentPrice = ticker?.bidPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val candles3m = candles3mDeferred.await()
        val prices = candles3m.mapNotNull { it.close.toBigDecimalOrNull() }

        // Calculate intraday indicators in parallel
        val intradayIndicators = calculateIntradayIndicators(prices)
        
        // Wait for 4H candles and calculate 4H indicators
        val candles4h = candles4hDeferred.await()
        val prices4h = candles4h.mapNotNull { it.close.toBigDecimalOrNull() }
        val indicators4h = calculate4HIndicators(candles4h, prices4h)

        // Wait for funding data
        val fundingRate = fundingRateDeferred.await()
        val openInterest = openInterestDeferred.await()

        return@coroutineScope CurrencyMarketData(
            symbol = symbol,
            currentPrice = currentPrice,
            currentEma20 = intradayIndicators.currentEma20,
            currentMacd = intradayIndicators.currentMacd,
            currentRsi7 = intradayIndicators.currentRsi7,
            openInterest = openInterest,
            fundingRate = fundingRate,
            intradayPrices = intradayIndicators.prices,
            intradayEma20 = intradayIndicators.ema20,
            intradayMacd = intradayIndicators.macd,
            intradayRsi7 = intradayIndicators.rsi7,
            intradayRsi14 = intradayIndicators.rsi14,
            ema20_4h = indicators4h.ema20,
            ema50_4h = indicators4h.ema50,
            atr3_4h = indicators4h.atr3,
            atr14_4h = indicators4h.atr14,
            volume4h = indicators4h.volume,
            avgVolume4h = indicators4h.avgVolume,
            macd4h = indicators4h.macd,
            rsi14_4h = indicators4h.rsi14
        )
    }

    /**
     * Calculate intraday indicators from 3-minute candle prices.
     * Runs calculations in parallel for better performance.
     */
    private suspend fun calculateIntradayIndicators(prices: List<BigDecimal>): IntradayIndicators = coroutineScope {
        val currentEma20 = async(Dispatchers.Default) { indicatorCache.calculateEMA(prices, EMA_PERIOD_20) }
        val currentMacd = async(Dispatchers.Default) { indicatorCache.calculateMACD(prices) }
        val currentRsi7 = async(Dispatchers.Default) { indicatorCache.calculateRSI(prices, RSI_PERIOD_7) }
        val intradayEma20 = async(Dispatchers.Default) { indicatorCache.calculateProgressiveEMA(prices, EMA_PERIOD_20) }
        val intradayMacd = async(Dispatchers.Default) { indicatorCache.calculateProgressiveMACD(prices) }
        val intradayRsi7 = async(Dispatchers.Default) { indicatorCache.calculateProgressiveRSI(prices, RSI_PERIOD_7) }
        val intradayRsi14 = async(Dispatchers.Default) { indicatorCache.calculateProgressiveRSI(prices, RSI_PERIOD_14) }

        IntradayIndicators(
            currentEma20 = currentEma20.await(),
            currentMacd = currentMacd.await(),
            currentRsi7 = currentRsi7.await(),
            prices = prices.takeLast(INTRADAY_SERIES_SIZE),
            ema20 = intradayEma20.await().takeLast(INTRADAY_SERIES_SIZE),
            macd = intradayMacd.await().takeLast(INTRADAY_SERIES_SIZE),
            rsi7 = intradayRsi7.await().takeLast(INTRADAY_SERIES_SIZE),
            rsi14 = intradayRsi14.await().takeLast(INTRADAY_SERIES_SIZE)
        )
    }

    /**
     * Calculate 4-hour timeframe indicators.
     * Runs calculations in parallel for better performance.
     */
    private suspend fun calculate4HIndicators(
        candles4h: List<OkxCandleResponse>,
        prices4h: List<BigDecimal>
    ): Indicators4H = coroutineScope {
        val ema20 = async(Dispatchers.Default) { indicatorCache.calculateEMA(prices4h, EMA_PERIOD_20) }
        val ema50 = async(Dispatchers.Default) { indicatorCache.calculateEMA(prices4h, EMA_PERIOD_50) }
        val atr3 = async(Dispatchers.Default) { indicatorCache.calculateATR(candles4h, ATR_PERIOD_3) }
        val atr14 = async(Dispatchers.Default) { indicatorCache.calculateATR(candles4h, ATR_PERIOD_14) }
        val macd = async(Dispatchers.Default) { indicatorCache.calculateProgressiveMACD(prices4h) }
        val rsi14 = async(Dispatchers.Default) { indicatorCache.calculateProgressiveRSI(prices4h, RSI_PERIOD_14) }

        val volume4h = candles4h.lastOrNull()?.volume?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val avgVolume4h = calculateAverageVolume(candles4h, last = VOLUME_AVG_PERIOD)

        Indicators4H(
            ema20 = ema20.await(),
            ema50 = ema50.await(),
            atr3 = atr3.await(),
            atr14 = atr14.await(),
            volume = volume4h,
            avgVolume = avgVolume4h,
            macd = padSeries(macd.await(), INTRADAY_SERIES_SIZE),
            rsi14 = padSeries(rsi14.await(), INTRADAY_SERIES_SIZE)
        )
    }

    /**
     * Pad a series to exactly the specified size with zeros if needed.
     */
    private fun padSeries(series: List<BigDecimal>, targetSize: Int): List<BigDecimal> {
        val lastValues = series.takeLast(targetSize)
        return if (lastValues.size < targetSize) {
            List(targetSize - lastValues.size) { BigDecimal.ZERO } + lastValues
        } else {
            lastValues
        }
    }

    /**
     * Data class for intraday indicators.
     */
    private data class IntradayIndicators(
        val currentEma20: BigDecimal,
        val currentMacd: BigDecimal,
        val currentRsi7: BigDecimal,
        val prices: List<BigDecimal>,
        val ema20: List<BigDecimal>,
        val macd: List<BigDecimal>,
        val rsi7: List<BigDecimal>,
        val rsi14: List<BigDecimal>
    )

    /**
     * Data class for 4-hour timeframe indicators.
     */
    private data class Indicators4H(
        val ema20: BigDecimal,
        val ema50: BigDecimal,
        val atr3: BigDecimal,
        val atr14: BigDecimal,
        val volume: BigDecimal,
        val avgVolume: BigDecimal,
        val macd: List<BigDecimal>,
        val rsi14: List<BigDecimal>
    )

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
                .divide(baseline, RETURN_CALCULATION_SCALE, RoundingMode.HALF_UP)
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
        return streamPositions().toList()
    }

    /**
     * Stream positions as they are parsed.
     * Uses Flow for reactive processing.
     */
    suspend fun streamPositions(): Flow<Position> {
        val positions = rest.fetchOpenPositions()
        return positions.asFlow()
            .mapNotNull { pos ->
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
        val newCandles = rest.fetchCandles(instId, CANDLE_TIMEFRAME_4H, CANDLE_COUNT_4H)

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
                .divide(BigDecimal(volumes.size), VOLUME_CALCULATION_SCALE, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    }

    override suspend fun loadInstrument(instrumentId: InstrumentId): TradeResult<OkxInstrumentInfo> {
        val instIdStr = instrumentId.value
        val cacheKey = "instrument:$instIdStr"
        
        return try {
            // Read-through pattern: Check cache -> Fetch if miss -> Write-through
            // Use getTyped for proper Redis deserialization support
            val instrumentInfo = smartCache.getTyped<ru.driics.aitrade.domain.model.OkxInstrumentInfo>(
                key = cacheKey,
                level = SmartCacheStrategy.CacheLevel.L3, // Use L3 for 1-hour TTL
                fetcher = {
                    rest.getSwapInstrument(instIdStr)
                        ?: throw IllegalArgumentException("Instrument $instIdStr not found")
                }
            )

            TradeResult.Success(instrumentInfo)
        } catch (e: IllegalArgumentException) {
            TradeResult.Failure.ApiError(
                code = "INSTRUMENT_NOT_FOUND",
                message = "Instrument $instIdStr not found"
            )
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
                code = "UNKNOWN_ERROR",
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
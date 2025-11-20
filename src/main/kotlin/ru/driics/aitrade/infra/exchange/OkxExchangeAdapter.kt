package ru.driics.aitrade.infra.exchange

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.*
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
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
import ru.driics.aitrade.infra.cache.CachedIndicatorCalculator
import ru.driics.aitrade.infra.cache.SmartCacheStrategy
import ru.driics.aitrade.infra.cache.getTyped
import ru.driics.aitrade.service.OkxRestClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service
class OkxExchangeAdapter(
    private val rest: OkxRestClient,
    private val tradingProperties: TradingProperties,
    private val tracer: Tracer,
    private val smartCache: SmartCacheStrategy,
    private val cachedIndicatorCalculator: CachedIndicatorCalculator,
    private val clock: Clock
) : MarketDataPort, TradingPort {

    private val log = KotlinLogging.logger { }

    private val sessionStartTime = clock.instant().toEpochMilli()
    private val invocationCount = AtomicLong(0L)
    private val initialAccountEquity = AtomicReference<BigDecimal?>(null)

    private val candles4HCache = ConcurrentHashMap<String, Cached4HData>()

    private companion object {
        const val CANDLE_3M = "3m"
        const val CANDLE_4H = "4H"
        const val COUNT_3M = 100
        const val COUNT_4H = 200
        const val INTRADAY_SIZE = 10
        const val AVG_VOL_PERIOD = 20

        const val EMA_20 = 20
        const val EMA_50 = 50
        const val RSI_7 = 7
        const val RSI_14 = 14
        const val ATR_3 = 3
        const val ATR_14 = 14

        const val MILLIS_PER_MINUTE = 60000L
        const val CACHE_KEY_PREFIX = "instrument:"
    }

    override suspend fun loadMarketState(symbols: List<Symbol>): MarketState = coroutineScope {
        val count = invocationCount.incrementAndGet()
        log.debug { "Loading market state for ${symbols.size} symbols (invocation #$count)" }

        val fetchStart = clock.instant().toEpochMilli()

        val currenciesDeferred = async(Dispatchers.IO) {
            val symbolStrings = symbols.map { it.value }
            streamCurrencyData(symbolStrings).toList()
        }

        val positionsDeferred = async(Dispatchers.IO) {
            tracer.traced("fetch_positions") { fetchPositions() }
        }

        val accountDeferred = async(Dispatchers.IO) {
            val positions = positionsDeferred.await()
            tracer.traced("fetch_account") { fetchAccountInfo(positions) }
        }

        val currencies = currenciesDeferred.await().associateBy { it.symbol }

        MarketState(
            timestamp = fetchStart,
            minutesSinceStart = (fetchStart - sessionStartTime) / MILLIS_PER_MINUTE,
            invocationCount = count,
            currencies = currencies,
            account = accountDeferred.await(),
            positions = positionsDeferred.await()
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamCurrencyData(symbols: List<String>): Flow<CurrencyMarketData> {
        return symbols.asFlow()
            .flatMapMerge(concurrency = tradingProperties.maxConcurrentSymbols) { symbol ->
                flow {
                    val data = runCatching {
                        withTimeout(5000L) { fetchCurrencyData(symbol) }
                    }.getOrElse { e ->
                        log.error(e) { "Failed to fetch data for $symbol" }
                        createEmptyCurrencyData(symbol)
                    }
                    emit(data)
                }
            }
    }

    suspend fun fetchCurrencyData(symbol: String): CurrencyMarketData = coroutineScope {
        val instId = InstrumentId.fromSymbol(symbol).value

        val tickerDef = async { rest.fetchTicker(instId) }
        val candles3mDef = async { rest.fetchCandles(instId, CANDLE_3M, COUNT_3M) }
        val candles4hDef = async { fetch4HCandlesWithCache(symbol, instId) }
        val fundingDef = async { rest.fetchFundingRate(instId) }
        val oiDef = async { rest.fetchOpenInterest(instId) }

        val candles3m = candles3mDef.await()
        val prices3m = candles3m.mapNotNull { it.close.toDecimal() }
        val intradayIndicatorsDef = async(Dispatchers.Default) {
            calculateIntradayIndicators(prices3m)
        }

        val candles4h = candles4hDef.await()
        val prices4h = candles4h.mapNotNull { it.close.toDecimal() }
        val indicators4hDef = async(Dispatchers.Default) {
            calculate4HIndicators(candles4h, prices4h)
        }

        val ticker = tickerDef.await()
        // ERROR 1 FIX: Handle nullability with default ZERO
        val currentPrice = ticker?.bidPrice.toDecimal() ?: BigDecimal.ZERO

        val intraday = intradayIndicatorsDef.await()
        val ind4h = indicators4hDef.await()

        CurrencyMarketData(
            symbol = symbol,
            currentPrice = currentPrice,
            currentEma20 = intraday.currentEma20,
            currentMacd = intraday.currentMacd,
            currentRsi7 = intraday.currentRsi7,
            openInterest = oiDef.await(),
            fundingRate = fundingDef.await(),
            intradayPrices = intraday.prices,
            intradayEma20 = intraday.ema20,
            intradayMacd = intraday.macd,
            intradayRsi7 = intraday.rsi7,
            intradayRsi14 = intraday.rsi14,
            ema20_4h = ind4h.ema20,
            ema50_4h = ind4h.ema50,
            atr3_4h = ind4h.atr3,
            atr14_4h = ind4h.atr14,
            volume4h = ind4h.volume,
            avgVolume4h = ind4h.avgVolume,
            macd4h = ind4h.macd,
            rsi14_4h = ind4h.rsi14
        )
    }

    private suspend fun calculateIntradayIndicators(prices: List<BigDecimal>): IntradayIndicators = coroutineScope {
        val c = cachedIndicatorCalculator
        val curEma20 = async { c.calculateEMA(prices, EMA_20) }
        val curMacd = async { c.calculateMACD(prices) }
        val curRsi7 = async { c.calculateRSI(prices, RSI_7) }

        val progEma20 = async { c.calculateProgressiveEMA(prices, EMA_20) }
        val progMacd = async { c.calculateProgressiveMACD(prices) }
        val progRsi7 = async { c.calculateProgressiveRSI(prices, RSI_7) }
        val progRsi14 = async { c.calculateProgressiveRSI(prices, RSI_14) }

        IntradayIndicators(
            currentEma20 = curEma20.await(),
            currentMacd = curMacd.await(),
            currentRsi7 = curRsi7.await(),
            prices = prices.takeLast(INTRADAY_SIZE),
            ema20 = progEma20.await().takeLast(INTRADAY_SIZE),
            macd = progMacd.await().takeLast(INTRADAY_SIZE),
            rsi7 = progRsi7.await().takeLast(INTRADAY_SIZE),
            rsi14 = progRsi14.await().takeLast(INTRADAY_SIZE)
        )
    }

    private suspend fun calculate4HIndicators(
        candles: List<OkxCandleResponse>,
        prices: List<BigDecimal>
    ): Indicators4H = coroutineScope {
        val c = cachedIndicatorCalculator

        val ema20 = async { c.calculateEMA(prices, EMA_20) }
        val ema50 = async { c.calculateEMA(prices, EMA_50) }
        val atr3 = async { c.calculateATR(candles, ATR_3) }
        val atr14 = async { c.calculateATR(candles, ATR_14) }
        val macd = async { c.calculateProgressiveMACD(prices) }
        val rsi14 = async { c.calculateProgressiveRSI(prices, RSI_14) }

        // ERROR 2 FIX: Handle nullability
        val volume = candles.lastOrNull()?.volume.toDecimal() ?: BigDecimal.ZERO
        val avgVol = calculateAverageVolume(candles, AVG_VOL_PERIOD)

        Indicators4H(
            ema20 = ema20.await(),
            ema50 = ema50.await(),
            atr3 = atr3.await(),
            atr14 = atr14.await(),
            volume = volume,
            avgVolume = avgVol,
            macd = padSeries(macd.await(), INTRADAY_SIZE),
            rsi14 = padSeries(rsi14.await(), INTRADAY_SIZE)
        )
    }

    private suspend fun fetch4HCandlesWithCache(symbol: String, instId: String): List<OkxCandleResponse> {
        val newCandles = rest.fetchCandles(instId, CANDLE_4H, COUNT_4H)
        if (newCandles.isEmpty()) {
            log.warn { "No 4H candles for $symbol" }
            return emptyList()
        }

        val lastTimestamp = newCandles.firstOrNull()?.timestamp?.toLongOrNull() ?: 0L
        val closePriceHash = newCandles.firstOrNull()?.close.hashCode()
        val newHash = 31 * lastTimestamp + closePriceHash

        val cached = candles4HCache[symbol]

        if (cached != null && cached.dataHash == newHash) {
            return cached.candles
        }

        candles4HCache[symbol] = Cached4HData(newCandles, lastTimestamp, newHash)
        return newCandles
    }

    private suspend fun fetchAccountInfo(prefetchedPositions: List<Position>): AccountInfo {
        val acc = rest.fetchAccount()
        val totalEquity = acc.totalEquity.toDecimal() ?: BigDecimal.ZERO
        val availableBalance = acc.availableBalance.toDecimal() ?: BigDecimal.ZERO

        // ERROR 3 FIX: Ensure baseline is non-null for comparison
        val baseline = initialAccountEquity.updateAndGet { current ->
            current ?: totalEquity
        } ?: totalEquity // Fallback only needed for strict compiler safety, though unlikely to be null here

        val totalReturnPct = if (baseline > BigDecimal.ZERO) {
            (totalEquity - baseline)
                .divide(baseline, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        } else BigDecimal.ZERO

        val effectiveCash = if (prefetchedPositions.isEmpty() && availableBalance.compareTo(BigDecimal.ZERO) == 0) {
            totalEquity
        } else {
            availableBalance
        }

        return AccountInfo(
            totalReturn = totalReturnPct,
            availableCash = effectiveCash,
            accountValue = totalEquity,
            sharpeRatio = null
        )
    }

    private suspend fun fetchPositions(): List<Position> {
        return rest.fetchOpenPositions().mapNotNull { pos ->
            runCatching {
                // ERROR 4 FIX: Use Elvis operator for mandatory BigDecimal fields
                Position(
                    symbol = pos.instrumentId.substringBefore("-"),
                    quantity = pos.quantity.toDecimal() ?: BigDecimal.ZERO,
                    entryPrice = pos.averagePrice.toDecimal() ?: BigDecimal.ZERO,
                    currentPrice = pos.markPrice.toDecimal() ?: BigDecimal.ZERO,
                    liquidationPrice = pos.liquidationPrice.toDecimal()?.takeIf { it > BigDecimal.ZERO },
                    unrealizedPnl = pos.unrealizedPnl.toDecimal() ?: BigDecimal.ZERO,
                    leverage = pos.leverage.toIntOrNull()
                )
            }.onFailure { log.warn { "Position parse error: ${pos.instrumentId}" } }.getOrNull()
        }
    }

    override suspend fun loadInstrument(instrumentId: InstrumentId): TradeResult<OkxInstrumentInfo> {
        val instId = instrumentId.value
        val key = "$CACHE_KEY_PREFIX$instId"

        return runCatching {
            // ERROR 5 FIX: Pass 'fetcher' as a named argument because it's not the last parameter in definition
            val info = smartCache.getTyped<OkxInstrumentInfo>(
                key = key,
                level = SmartCacheStrategy.CacheLevel.L3,
                fetcher = {
                    rest.getSwapInstrument(instId)
                        ?: throw IllegalArgumentException("Instrument not found")
                }
            )
            TradeResult.Success(info)
        }.fold(
            onSuccess = { it },
            onFailure = { e -> mapError(e, instId) }
        )
    }

    override suspend fun getLastPrice(instrumentId: InstrumentId): TradeResult<BigDecimal?> {
        val instId = instrumentId.value
        return runCatching {
            val t = rest.fetchTicker(instId)
            // ERROR 6 FIX: Safe call '?.takeIf' on result of 'toDecimal()'
            val price = t?.lastPrice.toDecimal()?.takeIf { it > BigDecimal.ZERO }
                ?: t?.askPrice.toDecimal()?.takeIf { it > BigDecimal.ZERO }
                ?: t?.bidPrice.toDecimal()?.takeIf { it > BigDecimal.ZERO }

            TradeResult.Success(price)
        }.getOrElse { mapError(it, instId) }
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
        return try {
            val res = rest.placeMarketOrderWithAttach(
                instId = instrumentId.value,
                side = side,
                tdMode = marginMode.asOkxApiValue,
                szContracts = contracts.quantizeToString(),
                tpPx = tp?.quantizeToString(tickSz),
                slPx = sl?.quantizeToString(tickSz),
                posSide = null,
                clOrdId = clOrdId,
                tag = tag
            )

            if (res != null && res.sCode == "0") {
                TradeResult.Success(PlaceOrderOutcome(true, res.ordId, "Placed"))
            } else {
                TradeResult.Failure.ApiError(
                    res?.sCode ?: "Unknown",
                    res?.sMsg ?: "Order rejected"
                )
            }
        } catch (e: Exception) {
            TradeResult.Failure.NetworkError("Order failed", e)
        }
    }

    override suspend fun setLeverage(instrumentId: InstrumentId, leverage: Int, marginMode: MarginMode): TradeResult<Boolean> {
        return runCatching {
            TradeResult.Success(rest.setLeverage(instrumentId.value, leverage, marginMode))
        }.getOrElse { mapError(it, instrumentId.value) }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun mapError(e: Throwable, id: String): TradeResult.Failure = when (e) {
        is ClientRequestException -> TradeResult.Failure.NetworkError("HTTP ${e.response.status}", e)
        is IllegalArgumentException -> TradeResult.Failure.ApiError("BAD_ARG", e.message ?: "")
        else -> TradeResult.Failure.ApiError("UNKNOWN", "Error on $id: ${e.message}", e)
    }

    private fun createEmptyCurrencyData(symbol: String) = CurrencyMarketData(
        symbol = symbol,
        currentPrice = BigDecimal.ZERO,
        currentEma20 = BigDecimal.ZERO,
        currentMacd = BigDecimal.ZERO,
        currentRsi7 = BigDecimal.ZERO
    )

    private fun padSeries(series: List<BigDecimal>, targetSize: Int): List<BigDecimal> {
        val last = series.takeLast(targetSize)
        return if (last.size < targetSize) {
            List(targetSize - last.size) { BigDecimal.ZERO } + last
        } else last
    }

    private fun calculateAverageVolume(candles: List<OkxCandleResponse>, limit: Int): BigDecimal {
        if (candles.isEmpty()) return BigDecimal.ZERO
        val subset = if (candles.size > limit) candles.takeLast(limit) else candles
        val sum = subset.mapNotNull { it.volume.toDecimal() }.fold(BigDecimal.ZERO, BigDecimal::add)
        return if (subset.isNotEmpty()) {
            sum.divide(BigDecimal(subset.size), 10, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
    }

    private fun String?.toDecimal(default: BigDecimal? = null): BigDecimal? {
        if (this.isNullOrBlank()) return default
        return try {
            BigDecimal(this)
        } catch (_: Exception) {
            default
        }
    }

    private fun BigDecimal.quantizeToString(tickSize: BigDecimal? = null): String {
        val q = if (tickSize != null) this.quantize(tickSize) else this
        return q.stripTrailingZeros().toPlainString()
    }

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

    private data class Cached4HData(
        val candles: List<OkxCandleResponse>,
        val lastCandleTimestamp: Long,
        val dataHash: Long
    )
}
package ru.driics.aitrade.infra.exchange

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.*
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    private val indicators: CachedIndicatorCalculator,
    private val clock: Clock
) : MarketDataPort, TradingPort {

    private val log = KotlinLogging.logger {}

    private val sessionStartTime = clock.millis()
    private val invocationCount = AtomicLong(0L)
    private val initialEquity = AtomicReference<BigDecimal?>(null)
    private val candles4HCache = ConcurrentHashMap<String, CachedCandles>()

    // =========================================================================
    // MarketDataPort Implementation
    // =========================================================================

    override suspend fun loadMarketState(symbols: List<Symbol>): MarketState = coroutineScope {
        val count = invocationCount.incrementAndGet()
        val fetchStart = clock.millis()

        log.debug { "Loading market state for ${symbols.size} symbols (#$count)" }

        val positionsDeferred = async(Dispatchers.IO) {
            tracer.traced("fetch_positions") { fetchPositions() }
        }

        val currenciesDeferred = async(Dispatchers.IO) {
            fetchAllCurrencyData(symbols.map(Symbol::value))
        }

        val positions = positionsDeferred.await()

        val accountDeferred = async(Dispatchers.IO) {
            tracer.traced("fetch_account") { fetchAccountInfo(positions) }
        }

        MarketState(
            timestamp = fetchStart,
            minutesSinceStart = (fetchStart - sessionStartTime) / MILLIS_PER_MINUTE,
            invocationCount = count,
            currencies = currenciesDeferred.await().associateBy(CurrencyMarketData::symbol),
            account = accountDeferred.await(),
            positions = positions
        )
    }

    override suspend fun loadInstrument(instrumentId: InstrumentId): TradeResult<OkxInstrumentInfo> {
        val instId = instrumentId.value

        return runCatching {
            val info = smartCache.getTyped<OkxInstrumentInfo>(
                key = "${CacheKeys.INSTRUMENT}$instId",
                level = SmartCacheStrategy.CacheLevel.L3,
                fetcher = {
                    rest.getSwapInstrument(instId)
                        ?: throw IllegalArgumentException("Instrument not found: $instId")
                }
            )
            TradeResult.Success(info)
        }.getOrElse { it.toTradeFailure(instId) }
    }

    override suspend fun getLastPrice(instrumentId: InstrumentId): TradeResult<BigDecimal?> {
        val instId = instrumentId.value

        return runCatching {
            val ticker = rest.fetchTicker(instId)
            val price = ticker?.run {
                sequenceOf(lastPrice, askPrice, bidPrice)
                    .firstNotNullOfOrNull { it.toPositiveBigDecimal() }
            }
            TradeResult.Success(price)
        }.getOrElse { it.toTradeFailure(instId) }
    }

    // =========================================================================
    // TradingPort Implementation
    // =========================================================================

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
    ): TradeResult<PlaceOrderOutcome> = runCatching {
        val response = rest.placeMarketOrderWithAttach(
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

        if (response?.sCode == "0") {
            TradeResult.Success(PlaceOrderOutcome(true, response.ordId, "Placed"))
        } else {
            TradeResult.Failure.ApiError(
                code = response?.sCode ?: "UNKNOWN",
                message = response?.sMsg ?: "Order rejected"
            )
        }
    }.getOrElse { e ->
        TradeResult.Failure.NetworkError("Order failed: ${e.message}", e)
    }

    override suspend fun setLeverage(
        instrumentId: InstrumentId,
        leverage: Int,
        marginMode: MarginMode
    ): TradeResult<Boolean> = runCatching {
        TradeResult.Success(rest.setLeverage(instrumentId.value, leverage, marginMode))
    }.getOrElse { it.toTradeFailure(instrumentId.value) }

    // =========================================================================
    // Currency Data Fetching
    // =========================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetchAllCurrencyData(symbols: List<String>): List<CurrencyMarketData> =
        symbols.asFlow()
            .flatMapMerge(concurrency = tradingProperties.maxConcurrentSymbols) { symbol ->
                flow { emit(fetchCurrencyDataSafe(symbol)) }
            }
            .toList()

    private suspend fun fetchCurrencyDataSafe(symbol: String): CurrencyMarketData =
        runCatching {
            withTimeout(Timeouts.CURRENCY_FETCH_MS) { fetchCurrencyData(symbol) }
        }.getOrElse { e ->
            log.error(e) { "Failed to fetch data for $symbol" }
            emptyCurrencyData(symbol)
        }

    private suspend fun fetchCurrencyData(symbol: String): CurrencyMarketData = coroutineScope {
        val instId = InstrumentId.fromSymbol(symbol).value

        // Parallel API calls
        val tickerDef = async { rest.fetchTicker(instId) }
        val candles3mDef = async { rest.fetchCandles(instId, Timeframe.M3, CandleCount.M3) }
        val candles4hDef = async { fetchCached4HCandles(symbol, instId) }
        val fundingDef = async { rest.fetchFundingRate(instId) }
        val openInterestDef = async { rest.fetchOpenInterest(instId) }

        val candles3m = candles3mDef.await()
        val prices3m = candles3m.extractClosePrices()

        val candles4h = candles4hDef.await()
        val prices4h = candles4h.extractClosePrices()

        // Calculate indicators in parallel
        val intradayDef = async(Dispatchers.Default) { calculateIntradayIndicators(prices3m) }
        val indicators4hDef = async(Dispatchers.Default) { calculate4HIndicators(candles4h, prices4h) }

        val ticker = tickerDef.await()
        val intraday = intradayDef.await()
        val ind4h = indicators4hDef.await()

        CurrencyMarketData(
            symbol = symbol,
            currentPrice = ticker?.bidPrice.toBigDecimalSafe(),
            currentEma20 = intraday.currentEma20,
            currentMacd = intraday.currentMacd,
            currentRsi7 = intraday.currentRsi7,
            openInterest = openInterestDef.await(),
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

    // =========================================================================
    // Indicator Calculations
    // =========================================================================

    private suspend fun calculateIntradayIndicators(prices: List<BigDecimal>): IntradayIndicators =
        coroutineScope {
            val currentEma20 = async { indicators.calculateEMA(prices, Period.EMA_20) }
            val currentMacd = async { indicators.calculateMACD(prices) }
            val currentRsi7 = async { indicators.calculateRSI(prices, Period.RSI_7) }
            val progressiveEma20 = async { indicators.calculateProgressiveEMA(prices, Period.EMA_20) }
            val progressiveMacd = async { indicators.calculateProgressiveMACD(prices) }
            val progressiveRsi7 = async { indicators.calculateProgressiveRSI(prices, Period.RSI_7) }
            val progressiveRsi14 = async { indicators.calculateProgressiveRSI(prices, Period.RSI_14) }

            IntradayIndicators(
                currentEma20 = currentEma20.await(),
                currentMacd = currentMacd.await(),
                currentRsi7 = currentRsi7.await(),
                prices = prices.takeLast(SERIES_SIZE),
                ema20 = progressiveEma20.await().takeLast(SERIES_SIZE),
                macd = progressiveMacd.await().takeLast(SERIES_SIZE),
                rsi7 = progressiveRsi7.await().takeLast(SERIES_SIZE),
                rsi14 = progressiveRsi14.await().takeLast(SERIES_SIZE)
            )
        }

    private suspend fun calculate4HIndicators(
        candles: List<OkxCandleResponse>,
        prices: List<BigDecimal>
    ): Indicators4H = coroutineScope {
        val ema20 = async { indicators.calculateEMA(prices, Period.EMA_20) }
        val ema50 = async { indicators.calculateEMA(prices, Period.EMA_50) }
        val atr3 = async { indicators.calculateATR(candles, Period.ATR_3) }
        val atr14 = async { indicators.calculateATR(candles, Period.ATR_14) }
        val macd = async { indicators.calculateProgressiveMACD(prices) }
        val rsi14 = async { indicators.calculateProgressiveRSI(prices, Period.RSI_14) }

        Indicators4H(
            ema20 = ema20.await(),
            ema50 = ema50.await(),
            atr3 = atr3.await(),
            atr14 = atr14.await(),
            volume = candles.lastOrNull()?.volume.toBigDecimalSafe(),
            avgVolume = candles.averageVolume(Period.AVG_VOLUME),
            macd = macd.await().padToSize(SERIES_SIZE),
            rsi14 = rsi14.await().padToSize(SERIES_SIZE)
        )
    }

    // =========================================================================
    // Account & Position Fetching
    // =========================================================================

    private suspend fun fetchAccountInfo(positions: List<Position>): AccountInfo {
        val account = rest.fetchAccount()
        val totalEquity = account.totalEquity.toBigDecimalSafe()
        val availableBalance = account.availableBalance.toBigDecimalSafe()

        val baseline = initialEquity.updateAndGet { it ?: totalEquity } ?: totalEquity

        val totalReturnPct = if (baseline > BigDecimal.ZERO) {
            (totalEquity - baseline)
                .divide(baseline, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
        } else {
            BigDecimal.ZERO
        }

        val effectiveCash = when {
            positions.isEmpty() && availableBalance.signum() == 0 -> totalEquity
            else -> availableBalance
        }

        return AccountInfo(
            totalReturn = totalReturnPct,
            availableCash = effectiveCash,
            accountValue = totalEquity,
            sharpeRatio = null
        )
    }

    private suspend fun fetchPositions(): List<Position> =
        rest.fetchOpenPositions().mapNotNull { pos ->
            runCatching {
                Position(
                    symbol = pos.instrumentId.substringBefore("-"),
                    quantity = pos.quantity.toBigDecimalSafe(),
                    entryPrice = pos.averagePrice.toBigDecimalSafe(),
                    currentPrice = pos.markPrice.toBigDecimalSafe(),
                    liquidationPrice = pos.liquidationPrice.toPositiveBigDecimal(),
                    unrealizedPnl = pos.unrealizedPnl.toBigDecimalSafe(),
                    leverage = pos.leverage.toIntOrNull()
                )
            }.onFailure {
                log.warn { "Failed to parse position: ${pos.instrumentId}" }
            }.getOrNull()
        }

    // =========================================================================
    // Caching
    // =========================================================================

    private suspend fun fetchCached4HCandles(symbol: String, instId: String): List<OkxCandleResponse> {
        val newCandles = rest.fetchCandles(instId, Timeframe.H4, CandleCount.H4)

        if (newCandles.isEmpty()) {
            log.warn { "No 4H candles received for $symbol" }
            return emptyList()
        }

        val latestCandle = newCandles.first()
        val newKey = CandleCacheKey(
            timestamp = latestCandle.timestamp?.toLongOrNull() ?: 0L,
            closeHash = latestCandle.close.hashCode()
        )

        val cached = candles4HCache[symbol]
        if (cached?.key == newKey) {
            return cached.candles
        }

        return newCandles.also {
            candles4HCache[symbol] = CachedCandles(candles = it, key = newKey)
        }
    }

    // =========================================================================
    // Extensions & Helpers
    // =========================================================================

    private fun Throwable.toTradeFailure(context: String): TradeResult.Failure = when (this) {
        is ClientRequestException -> TradeResult.Failure.NetworkError(
            message = "HTTP ${response.status}",
            cause = this
        )
        is IllegalArgumentException -> TradeResult.Failure.ApiError(
            code = "BAD_ARGUMENT",
            message = message ?: "Invalid argument"
        )
        else -> TradeResult.Failure.ApiError(
            code = "UNKNOWN",
            message = "Error for $context: ${message ?: "Unknown"}",
            cause = this
        )
    }

    private fun String?.toBigDecimalSafe(): BigDecimal =
        this?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun String?.toPositiveBigDecimal(): BigDecimal? =
        this!!.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

    private fun BigDecimal.quantizeToString(tickSize: BigDecimal? = null): String =
        (tickSize?.let { quantize(it) } ?: this)
            .stripTrailingZeros()
            .toPlainString()

    private fun List<OkxCandleResponse>.extractClosePrices(): List<BigDecimal> =
        mapNotNull { it.close.toBigDecimalOrNull() }

    private fun List<OkxCandleResponse>.averageVolume(period: Int): BigDecimal {
        if (isEmpty()) return BigDecimal.ZERO

        val volumes = takeLast(period).mapNotNull { it.volume.toBigDecimalOrNull() }
        if (volumes.isEmpty()) return BigDecimal.ZERO

        return volumes.reduce(BigDecimal::add)
            .divide(volumes.size.toBigDecimal(), 10, RoundingMode.HALF_UP)
    }

    private fun List<BigDecimal>.padToSize(targetSize: Int): List<BigDecimal> {
        val tail = takeLast(targetSize)
        val padding = targetSize - tail.size
        return if (padding > 0) List(padding) { BigDecimal.ZERO } + tail else tail
    }

    private fun emptyCurrencyData(symbol: String) = CurrencyMarketData(
        symbol = symbol,
        currentPrice = BigDecimal.ZERO,
        currentEma20 = BigDecimal.ZERO,
        currentMacd = BigDecimal.ZERO,
        currentRsi7 = BigDecimal.ZERO
    )

    // =========================================================================
    // Data Classes
    // =========================================================================

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

    private data class CandleCacheKey(
        val timestamp: Long,
        val closeHash: Int
    )

    private data class CachedCandles(
        val candles: List<OkxCandleResponse>,
        val key: CandleCacheKey
    )

    // =========================================================================
    // Constants
    // =========================================================================

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val SERIES_SIZE = 10
        val HUNDRED: BigDecimal = BigDecimal(100)
    }

    private object Timeframe {
        const val M3 = "3m"
        const val H4 = "4H"
    }

    private object CandleCount {
        const val M3 = 100
        const val H4 = 200
    }

    private object Period {
        const val EMA_20 = 20
        const val EMA_50 = 50
        const val RSI_7 = 7
        const val RSI_14 = 14
        const val ATR_3 = 3
        const val ATR_14 = 14
        const val AVG_VOLUME = 20
    }

    private object Timeouts {
        const val CURRENCY_FETCH_MS = 5_000L
    }

    private object CacheKeys {
        const val INSTRUMENT = "instrument:"
    }
}
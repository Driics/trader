package ru.driics.aitrade.infra.exchange

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.MarginMode
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PlaceOrderOutcome
import ru.driics.aitrade.domain.ports.TradingPort
import ru.driics.aitrade.domain.util.quantize
import ru.driics.aitrade.domain.services.IndicatorCalculator
import ru.driics.aitrade.model.*
import ru.driics.aitrade.service.OkxRestClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service
class OkxExchangeAdapter(
    private val rest: OkxRestClient,
    private val tradingProperties: TradingProperties
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

    override suspend fun loadMarketState(symbols: List<String>): MarketState = withContext(Dispatchers.IO) {
        val count = invocationCount.incrementAndGet()
        log.debug { "Loading market state for ${symbols.size} symbols (invocation #$count)" }

        val semaphore = Semaphore(tradingProperties.maxConcurrentSymbols)

        val currencies = symbols.associateWith { symbol ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    withTimeout(5000) {
                        runCatching {
                            fetchCurrencyData(symbol)
                        }.getOrElse { e ->
                            log.error(e) { "Failed to fetch data for $symbol" }
                            createEmptyCurrencyData(symbol)
                        }
                    }
                }
            }
        }.mapValues { it.value.await() }

        val positions = fetchPositions()
        val account = fetchAccountInfo(positions)

        MarketState(
            timestamp = System.currentTimeMillis(),
            minutesSinceStart = (System.currentTimeMillis() - sessionStartTime.get()) / 60000,
            invocationCount = count,
            currencies = currencies,
            account = account,
            positions = positions
        )
    }

    private suspend fun fetchCurrencyData(symbol: String): CurrencyMarketData {
        val instId = "${symbol}-USDT-SWAP"

        // Fetch current ticker
        val ticker = rest.fetchTicker(instId)
        val currentPrice = ticker?.bidPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        // Fetch 3-minute candles
        val candles = rest.fetchCandles(instId, "3m", 100)
        val prices = candles.mapNotNull { it.close.toBigDecimalOrNull() }

        // Calculate current indicators
        val ema20 = IndicatorCalculator.calculateEMA(prices, 20)
        val macd = IndicatorCalculator.calculateMACD(prices)
        val rsi7 = IndicatorCalculator.calculateRSI(prices, 7)

        // Calculate progressive indicators for intraday series
        val intradayEma20 = IndicatorCalculator.calculateProgressiveEMA(prices, 20)
        val intradayMacd = IndicatorCalculator.calculateProgressiveMACD(prices)
        val intradayRsi7 = IndicatorCalculator.calculateProgressiveRSI(prices, 7)
        val intradayRsi14 = IndicatorCalculator.calculateProgressiveRSI(prices, 14)

        // Fetch 4-hour candles for longer-term context
        val candles4h = fetch4HCandlesWithCache(symbol, instId)
        val prices4h = candles4h.mapNotNull { it.close.toBigDecimalOrNull() }

        val ema20_4h = IndicatorCalculator.calculateEMA(prices4h, 20)
        val ema50_4h = IndicatorCalculator.calculateEMA(prices4h, 50)
        val atr3_4h = IndicatorCalculator.calculateATR(candles4h, 3)
        val atr14_4h = IndicatorCalculator.calculateATR(candles4h, 14)

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
        val fundingRate = rest.fetchFundingRate(instId)
        val openInterest = rest.fetchOpenInterest(instId)

        return CurrencyMarketData(
            symbol = symbol,
            currentPrice = currentPrice,
            currentEma20 = ema20,
            currentMacd = macd,
            currentRsi7 = rsi7,
            openInterest = openInterest,
            fundingRate = fundingRate,
            intradayPrices = prices.takeLast(10),
            intradayEma20 = intradayEma20.takeLast(10),
            intradayMacd = intradayMacd.takeLast(10),
            intradayRsi7 = intradayRsi7.takeLast(10),
            intradayRsi14 = intradayRsi14.takeLast(10),
            ema20_4h = ema20_4h,
            ema50_4h = ema50_4h,
            atr3_4h = atr3_4h,
            atr14_4h = atr14_4h,
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
        val lastTimestamp = newCandles.lastOrNull()?.timestamp?.toLongOrNull() ?: 0L
        
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

    override suspend fun loadInstrument(instId: String): OkxInstrumentInfo? =
        withContext(Dispatchers.IO) {
            try {
                instrumentCache.get(instId) {
                    runBlocking { rest.getSwapInstrument(instId) }
                        ?: error("Instrument $instId not found")
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to load instrument $instId" }
                null
            }
        }

    override suspend fun getLastPrice(instId: String): BigDecimal? = withContext(Dispatchers.IO) {
        try {
            val t = rest.fetchTicker(instId)
            t?.lastPrice?.toBigDecimalOrNull()
                ?: t?.askPrice?.toBigDecimalOrNull()
                ?: t?.bidPrice?.toBigDecimalOrNull()
        } catch (e: Exception) {
            log.error(e) { "Failed to get last price for $instId" }
            null
        }
    }

    override suspend fun setLeverage(instId: String, leverage: Int, marginMode: MarginMode): Boolean =
        withContext(Dispatchers.IO) {
            try {
                rest.setLeverage(
                    instId,
                    leverage,
                    marginMode
                )
            } catch (e: Exception) {
                log.error(e) { "Failed to set leverage $leverage for $instId in ${marginMode.asOkxApiValue} mode" }
                false
            }
        }

    override suspend fun placeMarketOrderWithTpSl(
        instId: String,
        side: String,
        contracts: BigDecimal,
        tp: BigDecimal?,
        sl: BigDecimal?,
        tickSz: BigDecimal,
        clOrdId: String,
        tag: String?,
        marginMode: MarginMode
    ): PlaceOrderOutcome = withContext(Dispatchers.IO) {
        try {
            val tpStr = tp?.quantize(tickSz)?.toPlainString()
            val slStr = sl?.quantize(tickSz)?.toPlainString()

            val res = rest.placeMarketOrderWithAttach(
                instId = instId,
                side = side,
                tdMode = marginMode.asOkxApiValue,
                szContracts = contracts.stripTrailingZeros().toPlainString(),
                tpPx = tpStr,
                slPx = slStr,
                posSide = null,
                clOrdId = clOrdId,
                tag = tag
            )

            val ok = (res?.sCode == "0")
            PlaceOrderOutcome(
                ok = ok,
                ordId = res?.ordId,
                message = if (ok) "OK" else (res?.sMsg ?: "Unknown error")
            )
        } catch (e: Exception) {
            log.error(e) { "Failed to place order for $instId" }
            PlaceOrderOutcome(ok = false, ordId = null, message = e.message ?: "Exception")
        }
    }
}
package ru.driics.aitrade.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import ru.driics.aitrade.model.*
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

@Service
class OkxMarketDataService(
    private val okxHttpClient: OkxHttpClient,
    private val technicalIndicatorService: TechnicalIndicatorService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessionStartTime: AtomicLong = AtomicLong(System.currentTimeMillis())
    private val invocationCount: AtomicLong = AtomicLong(0L)

    fun fetchMarketData(symbols: List<String>): Map<String, CurrencyMarketData> {
        invocationCount.incrementAndGet()
        log.info("Fetching market data for symbols: $symbols (invocation #$invocationCount)")

        return symbols.associate { symbol ->
            try {
                val data = fetchCurrencyData(symbol)
                symbol to data
            } catch (e: Exception) {
                log.error("Error fetching data for $symbol", e)
                symbol to createEmptyCurrencyData(symbol)
            }
        }
    }

    private fun fetchCurrencyData(symbol: String): CurrencyMarketData {
        val instId = "${symbol}-USDT-SWAP"

        // Fetch ticker
        val ticker = okxHttpClient.fetchTicker(instId)
        val currentPrice = ticker?.bidPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        // Fetch 3-minute candles
        val candles = okxHttpClient.fetchCandles(instId, "3m", 50)
        val prices = candles.mapNotNull { it.close.toBigDecimalOrNull() }

        // Calculate current indicators
        val ema20 = technicalIndicatorService.calculateEMA(prices, 20)
        val macd = technicalIndicatorService.calculateMACD(prices)
        val rsi7 = technicalIndicatorService.calculateRSI(prices, 7)
        val rsi14 = technicalIndicatorService.calculateRSI(prices, 14)

        // Calculate intraday series (progressive calculations return oldest → latest)
        val intradayEma20Full = technicalIndicatorService.calculateProgressiveEMA(prices, 20)
        val intradayMacdFull = technicalIndicatorService.calculateProgressiveMACD(prices)
        val intradayRsi7Full = technicalIndicatorService.calculateProgressiveRSI(prices, 7)
        val intradayRsi14Full = technicalIndicatorService.calculateProgressiveRSI(prices, 14)

        // Fetch 4-hour data
        val candles4h = okxHttpClient.fetchCandles(instId, "4H", 50)
        val prices4h = candles4h.mapNotNull { it.close.toBigDecimalOrNull() }

        val ema20_4h = technicalIndicatorService.calculateEMA(prices4h, 20)
        val ema50_4h = technicalIndicatorService.calculateEMA(prices4h, 50)
        val atr3_4h = technicalIndicatorService.calculateATR(candles4h, 3)
        val atr14_4h = technicalIndicatorService.calculateATR(candles4h, 14)

        val volume4h = candles4h.lastOrNull()?.volume?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val avgVolume4h = calculateAverageVolume(candles4h)

        val macd4hFull = technicalIndicatorService.calculateProgressiveMACD(prices4h)
        val rsi14_4hFull = technicalIndicatorService.calculateProgressiveRSI(prices4h, 14)

        // Fetch funding rate and open interest
        val fundingRate = okxHttpClient.fetchFundingRate(instId)
        val openInterest = okxHttpClient.fetchOpenInterest(instId)

        return CurrencyMarketData(
            symbol = symbol,
            currentPrice = currentPrice,
            currentEma20 = ema20,
            currentMacd = macd,
            currentRsi7 = rsi7,
            openInterest = openInterest,
            fundingRate = fundingRate,

            // ✅ FIXED: Limit to last 10 values (oldest → latest)
            intradayPrices = prices.takeLast(10),
            intradayEma20 = intradayEma20Full.takeLast(10),
            intradayMacd = intradayMacdFull.takeLast(10),
            intradayRsi7 = intradayRsi7Full.takeLast(10),
            intradayRsi14 = intradayRsi14Full.takeLast(10),

            ema20_4h = ema20_4h,
            ema50_4h = ema50_4h,
            atr3_4h = atr3_4h,
            atr14_4h = atr14_4h,
            volume4h = volume4h,
            avgVolume4h = avgVolume4h,

            // ✅ FIXED: Limit 4-hour series to last 10 values
            macd4h = macd4hFull.takeLast(10),
            rsi14_4h = rsi14_4hFull.takeLast(10)
        )
    }

    fun fetchAccountInfo(): AccountInfo {
        log.info("Fetching account information")
        return try {
            val account = okxHttpClient.fetchAccount()
            val totalEquity = account.totalEquity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val availableBalance = account.availableBalance.toBigDecimalOrNull() ?: BigDecimal.ZERO

            AccountInfo(
                totalReturn = if (totalEquity > BigDecimal.ZERO) {
                    ((totalEquity - BigDecimal(10000)) / BigDecimal(10000) * BigDecimal(100))
                } else {
                    BigDecimal.ZERO
                },
                availableCash = availableBalance,
                accountValue = totalEquity,
                sharpeRatio = null
            )
        } catch (e: Exception) {
            log.error("Error fetching account info", e)
            AccountInfo(
                totalReturn = BigDecimal.ZERO,
                availableCash = BigDecimal.ZERO,
                accountValue = BigDecimal.ZERO
            )
        }
    }

    fun fetchPositions(): List<Position> {
        log.info("Fetching positions")
        return try {
            val positions = okxHttpClient.fetchOpenPositions()
            positions.mapNotNull { pos ->
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
                    log.error("Error parsing position", e)
                    null
                }
            }
        } catch (e: Exception) {
            log.error("Error fetching positions", e)
            emptyList()
        }
    }

    fun getSessionStartTime(): Long = sessionStartTime.get()
    fun getInvocationCount(): Long = invocationCount.get()

    private fun calculateAverageVolume(candles: List<OkxCandleResponse>): BigDecimal {
        if (candles.isEmpty()) return BigDecimal.ZERO
        val volumes = candles.mapNotNull { it.volume.toBigDecimalOrNull() }
        return if (volumes.isNotEmpty()) {
            volumes.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(volumes.size), 10, java.math.RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    }

    private fun createEmptyCurrencyData(symbol: String) = CurrencyMarketData(
        symbol = symbol,
        currentPrice = BigDecimal.ZERO,
        currentEma20 = BigDecimal.ZERO,
        currentMacd = BigDecimal.ZERO,
        currentRsi7 = BigDecimal.ZERO
    )
}
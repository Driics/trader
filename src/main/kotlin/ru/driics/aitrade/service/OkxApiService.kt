package ru.driics.aitrade.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

@Service
class OkxApiService(
    private val okxProperties: OkxProperties,
    private val restTemplate: RestTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    // Default start time for tracking minutes
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
                symbol to CurrencyMarketData(
                    symbol = symbol,
                    currentPrice = BigDecimal.ZERO,
                    currentEma20 = BigDecimal.ZERO,
                    currentMacd = BigDecimal.ZERO,
                    currentRsi7 = BigDecimal.ZERO
                )
            }
        }
    }

    private fun fetchCurrencyData(symbol: String): CurrencyMarketData {
        val instId = "${symbol}-USDT-SWAP"
        
        // Fetch ticker data
        val ticker = fetchTicker(instId)
        val currentPrice = ticker?.bidPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        // Fetch candle data for 3-minute intervals (need at least 26 for MACD)
        val candles = fetchCandles(instId, "3m", 50)
        val prices = candles.mapNotNull { it.close.toBigDecimalOrNull() }

        // Calculate indicators once
        val ema20 = calculateEMA(prices, 20)
        val macd = calculateMACD(prices)
        val rsi7 = calculateRSI(prices, 7)
        val rsi14 = calculateRSI(prices, 14)

        // Efficiently calculate intraday series using progressive calculation
        val intradayEma20 = calculateProgressiveEMA(prices, 20)
        val intradayMacd = calculateProgressiveMACD(prices)
        val intradayRsi7 = calculateProgressiveRSI(prices, 7)
        val intradayRsi14 = calculateProgressiveRSI(prices, 14)


        // Fetch 4-hour data
        val candles4h = fetchCandles(instId, "4h", 10)
        val prices4h = candles4h.mapNotNull { it.close.toBigDecimalOrNull() }

        val ema20_4h = calculateEMA(prices4h, 20)
        val ema50_4h = calculateEMA(prices4h, 50)
        val atr3_4h = calculateATR(candles4h, 3)
        val atr14_4h = calculateATR(candles4h, 14)

        val volume4h = candles4h.lastOrNull()?.volume?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val avgVolume4h = if (candles4h.isNotEmpty()) {
            val volumes = candles4h.mapNotNull { it.volume.toBigDecimalOrNull() }
            if (volumes.isNotEmpty()) volumes.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(volumes.size), 10, java.math.RoundingMode.HALF_UP)
            else BigDecimal.ZERO
        } else {
            BigDecimal.ZERO
        }

        val macd4h = candles4h.mapNotNull { it.close.toBigDecimalOrNull() }
            .let { prices -> (0 until prices.size).map { calculateMACD(prices.subList(0, it + 1)) } }

        val rsi14_4h = candles4h.mapNotNull { it.close.toBigDecimalOrNull() }
            .let { prices -> (0 until prices.size).map { calculateRSI(prices.subList(0, it + 1), 14) } }

        // Fetch funding rate and open interest
        val fundingRate = fetchFundingRate(instId)
        val openInterest = fetchOpenInterest(instId)

        return CurrencyMarketData(
            symbol = symbol,
            currentPrice = currentPrice,
            currentEma20 = ema20,
            currentMacd = macd,
            currentRsi7 = rsi7,
            openInterest = openInterest,
            fundingRate = fundingRate,
            intradayPrices = prices,
            intradayEma20 = intradayEma20,
            intradayMacd = intradayMacd,
            intradayRsi7 = intradayRsi7,
            intradayRsi14 = intradayRsi14,
            ema20_4h = ema20_4h,
            ema50_4h = ema50_4h,
            atr3_4h = atr3_4h,
            atr14_4h = atr14_4h,
            volume4h = volume4h,
            avgVolume4h = avgVolume4h,
            macd4h = macd4h,
            rsi14_4h = rsi14_4h
        )
    }

    fun fetchAccountInfo(): AccountInfo {
        log.info("Fetching account information")
        return try {
            val account = fetchAccount()
            val totalEquity = account.totalEquity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val availableBalance = account.availableBalance.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val cashBalance = account.cashBalance.toBigDecimalOrNull() ?: BigDecimal.ZERO

            AccountInfo(
                totalReturn = if (totalEquity > BigDecimal.ZERO) {
                    ((totalEquity - BigDecimal(10000)) / BigDecimal(10000) * BigDecimal(100))
                } else {
                    BigDecimal.ZERO
                },
                availableCash = availableBalance,
                accountValue = totalEquity,
                sharpeRatio = null // Would need historical data to calculate
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
            val positions = fetchOpenPositions()
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

    // Private API call methods
    private fun fetchTicker(instId: String): OkxTickerResponse? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/ticker?instId=$instId"
            val response = restTemplate.getForObject(url, String::class.java) ?: return null
            
            val jsonNode = objectMapper.readTree(response)
            val data = jsonNode.get("data")?.get(0) ?: return null
            
            OkxTickerResponse(
                instrumentId = data.get("instId")?.asText() ?: instId,
                lastPrice = data.get("last")?.asText() ?: "0",
                askPrice = data.get("askPx")?.asText() ?: "0",
                bidPrice = data.get("bidPx")?.asText() ?: "0",
                timestamp = data.get("ts")?.asText() ?: ""
            )
        } catch (e: Exception) {
            log.error("Error fetching ticker for $instId", e)
            null
        }
    }

    private fun fetchCandles(instId: String, period: String, limit: Int): List<OkxCandleResponse> {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/market/candles?instId=$instId&bar=$period&limit=$limit"
            val response = restTemplate.getForObject(url, String::class.java) ?: return emptyList()
            
            val jsonNode = objectMapper.readTree(response)
            val candleArray = jsonNode.get("data") ?: return emptyList()

            val parsed = candleArray.map { candle ->
                val values = candle.map { it.asText() }
                OkxCandleResponse(
                    timestamp = values.getOrNull(0) ?: "",
                    open = values.getOrNull(1) ?: "0",
                    high = values.getOrNull(2) ?: "0",
                    low = values.getOrNull(3) ?: "0",
                    close = values.getOrNull(4) ?: "0",
                    volume = values.getOrNull(5) ?: "0",
                    volumeCcy = values.getOrNull(6) ?: "0"
                )
            }

            parsed.sortedBy { it.timestamp.toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: Exception) {
            log.error("Error fetching candles for $instId", e)
            emptyList()
        }
    }

    private fun fetchFundingRate(instId: String): BigDecimal? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/funding-rate?instId=$instId"
            val response = restTemplate.getForObject(url, String::class.java) ?: return null
            
            val jsonNode = objectMapper.readTree(response)
            val data = jsonNode.get("data")?.get(0) ?: return null
            
            data.get("fundingRate")?.asText()?.toBigDecimalOrNull()
        } catch (e: Exception) {
            log.error("Error fetching funding rate for $instId", e)
            null
        }
    }

    private fun fetchOpenInterest(instId: String): BigDecimal? {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/public/open-interest?instId=$instId"
            val response = restTemplate.getForObject(url, String::class.java) ?: return null
            
            val jsonNode = objectMapper.readTree(response)
            val data = jsonNode.get("data")?.get(0) ?: return null
            
            data.get("oi")?.asText()?.toBigDecimalOrNull()
        } catch (e: Exception) {
            log.error("Error fetching open interest for $instId", e)
            null
        }
    }

    private fun fetchAccount(): OkxAccountResponse {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/account/account-info"
            val headers = createAuthHeaders("GET", "/api/v5/account/account-info", "")
            val entity = HttpEntity<String>(headers)
            
            val response = restTemplate.exchange(url, HttpMethod.GET, entity, String::class.java)
            val body = response.body ?: return OkxAccountResponse("0", "0", "0", "0")
            
            val jsonNode = objectMapper.readTree(body)
            val code = jsonNode.get("code")?.asText()
            if (code != "0") {
                val msg = jsonNode.get("msg")?.asText() ?: "Unknown error"
                log.warn("OKX API returned error code: $code, message: $msg")
            }
            
            val data = jsonNode.get("data")?.get(0) ?: return OkxAccountResponse("0", "0", "0", "0")
            
            OkxAccountResponse(
                totalEquity = data.get("totalEq")?.asText() ?: "0",
                availableBalance = data.get("availBal")?.asText() ?: "0",
                cashBalance = data.get("cashBal")?.asText() ?: "0",
                unrealizedPnl = data.get("upl")?.asText() ?: "0"
            )
        } catch (e: Exception) {
            log.error("Error fetching account info", e)
            OkxAccountResponse("0", "0", "0", "0")
        }
    }

    private fun fetchOpenPositions(): List<OkxPositionResponse> {
        return try {
            val url = "${okxProperties.baseUrl}/api/v5/account/positions?instType=SWAP"
            val headers = createAuthHeaders("GET", "/api/v5/account/positions?instType=SWAP", "")
            val entity = HttpEntity<String>(headers)
            
            val response = restTemplate.exchange(url, HttpMethod.GET, entity, String::class.java)
            val body = response.body ?: return emptyList()
            
            val jsonNode = objectMapper.readTree(body)
            val code = jsonNode.get("code")?.asText()
            if (code != "0") {
                val msg = jsonNode.get("msg")?.asText() ?: "Unknown error"
                log.warn("OKX API returned error code: $code, message: $msg")
            }
            
            val dataArray = jsonNode.get("data") ?: return emptyList()
            
            dataArray.mapNotNull { position ->
                try {
                    OkxPositionResponse(
                        instrumentId = position.get("instId")?.asText() ?: return@mapNotNull null,
                        positionId = position.get("posId")?.asText() ?: "",
                        positionSide = position.get("posSide")?.asText() ?: "",
                        quantity = position.get("pos")?.asText() ?: "0",
                        averagePrice = position.get("avgPx")?.asText() ?: "0",
                        marginMode = position.get("mgnMode")?.asText() ?: "",
                        leverage = position.get("lever")?.asText() ?: "1",
                        liquidationPrice = position.get("liqPx")?.asText() ?: "0",
                        markPrice = position.get("markPx")?.asText() ?: "0",
                        unrealizedPnl = position.get("upl")?.asText() ?: "0",
                        unrealizedPnlRatio = position.get("uplRatio")?.asText() ?: "0",
                        createTime = position.get("cTime")?.asText() ?: "",
                        updateTime = position.get("uTime")?.asText() ?: ""
                    )
                } catch (e: Exception) {
                    log.error("Error parsing position", e)
                    null
                }
            }
        } catch (e: Exception) {
            log.error("Error fetching open positions", e)
            emptyList()
        }
    }
    
    private fun createAuthHeaders(method: String, requestPath: String, body: String): HttpHeaders {

        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        
        val message = "$timestamp$method$requestPath$body"
        
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(okxProperties.secret.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray()))
        
        log.debug("Auth - Timestamp: $timestamp, Path: $requestPath, Signature: $signature")
        
        return HttpHeaders().apply {
            set("OK-ACCESS-KEY", okxProperties.key)
            set("OK-ACCESS-SIGN", signature)
            set("OK-ACCESS-TIMESTAMP", timestamp)
            set("OK-ACCESS-PASSPHRASE", okxProperties.passphrase)
            contentType = MediaType.APPLICATION_JSON
        }
    }

    // Technical Indicator Calculations
    private fun calculateEMA(prices: List<BigDecimal>, period: Int): BigDecimal {
        if (prices.isEmpty() || period <= 0) return BigDecimal.ZERO
        if (prices.size < period) return prices.fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(prices.size), 10, java.math.RoundingMode.HALF_UP)

        val k = BigDecimal(2).divide(BigDecimal(period + 1), 10, java.math.RoundingMode.HALF_UP)
        var ema = prices.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)

        for (i in period until prices.size) {
            ema = prices[i].multiply(k).add(ema.multiply(BigDecimal.ONE.subtract(k)))
        }

        return ema
    }

    private fun calculateMACD(prices: List<BigDecimal>): BigDecimal {
        if (prices.size < 26) return BigDecimal.ZERO
        
        val ema12 = calculateEMA(prices, 12)
        val ema26 = calculateEMA(prices, 26)
        return ema12 - ema26
    }

    private fun calculateRSI(prices: List<BigDecimal>, period: Int): BigDecimal {
        if (prices.size < period + 1) return BigDecimal.ZERO

        val changes = prices.zipWithNext { a, b -> b - a }
        val gains = changes.map { if (it > BigDecimal.ZERO) it else BigDecimal.ZERO }
        val losses = changes.map { if (it < BigDecimal.ZERO) it.abs() else BigDecimal.ZERO }

        if (gains.size < period) return BigDecimal.ZERO

        var avgGain = gains.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)
        var avgLoss = losses.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)

        for (i in period until gains.size) {
            avgGain = (avgGain.multiply(BigDecimal(period - 1)).add(gains[i]))
                .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)
            avgLoss = (avgLoss.multiply(BigDecimal(period - 1)).add(losses[i]))
                .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)
        }

        return if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal(100)
        } else {
            val rs = avgGain.divide(avgLoss, 10, java.math.RoundingMode.HALF_UP)
            BigDecimal(100).subtract(BigDecimal(100).divide(BigDecimal.ONE.add(rs), 10, java.math.RoundingMode.HALF_UP))
        }
    }

    private fun calculateATR(candles: List<OkxCandleResponse>, period: Int): BigDecimal {
        if (candles.size < 2 || period <= 0) return BigDecimal.ZERO

        val trueRanges = mutableListOf<BigDecimal>()

        for (i in 1 until candles.size) {
            val high = candles[i].high.toBigDecimalOrNull() ?: continue
            val low = candles[i].low.toBigDecimalOrNull() ?: continue
            val prevClose = candles[i-1].close.toBigDecimalOrNull() ?: continue

            val tr = maxOf(
                high - low,
                (high - prevClose).abs(),
                (low - prevClose).abs()
            )
            trueRanges.add(tr)
        }

        if (trueRanges.isEmpty()) return BigDecimal.ZERO

        return if (trueRanges.size < period) {
            trueRanges.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(trueRanges.size), 10, java.math.RoundingMode.HALF_UP)
        } else {
            // Use Wilder's smoothing for ATR
            var atr = trueRanges.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)
            for (i in period until trueRanges.size) {
                atr = (atr.multiply(BigDecimal(period - 1)).add(trueRanges[i]))
                    .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)
            }
            atr
        }
    }

    private fun calculateProgressiveEMA(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (prices.size < period) return prices.map { BigDecimal.ZERO }

        val result = mutableListOf<BigDecimal>()
        val k = BigDecimal(2).divide(BigDecimal(period + 1), 10, java.math.RoundingMode.HALF_UP)

        // Initial SMA
        var ema = prices.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)

        // Add zeros for initial period
        repeat(period - 1) { result.add(BigDecimal.ZERO) }
        result.add(ema)

        // Progressive EMA calculation
        for (i in period until prices.size) {
            ema = prices[i].multiply(k).add(ema.multiply(BigDecimal.ONE.subtract(k)))
            result.add(ema)
        }

        return result
    }

    private fun calculateProgressiveMACD(prices: List<BigDecimal>): List<BigDecimal> {
        if (prices.size < 26) return prices.map { BigDecimal.ZERO }

        val ema12List = calculateProgressiveEMA(prices, 12)
        val ema26List = calculateProgressiveEMA(prices, 26)

        return ema12List.zip(ema26List) { ema12, ema26 -> ema12 - ema26 }
    }

    private fun calculateProgressiveRSI(prices: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (prices.size < period + 1) return prices.map { BigDecimal.ZERO }

        val result = mutableListOf<BigDecimal>()
        val changes = prices.zipWithNext { a, b -> b - a }

        // Add zeros for initial period
        repeat(period) { result.add(BigDecimal.ZERO) }

        val gains = changes.map { if (it > BigDecimal.ZERO) it else BigDecimal.ZERO }
        val losses = changes.map { if (it < BigDecimal.ZERO) it.abs() else BigDecimal.ZERO }

        var avgGain = gains.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)
        var avgLoss = losses.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)

        // Calculate first RSI
        val firstRsi = if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal(100)
        } else {
            val rs = avgGain.divide(avgLoss, 10, java.math.RoundingMode.HALF_UP)
            BigDecimal(100).subtract(BigDecimal(100).divide(BigDecimal.ONE.add(rs), 10, java.math.RoundingMode.HALF_UP))
        }
        result.add(firstRsi)

        // Progressive RSI
        for (i in period until gains.size) {
            avgGain = (avgGain.multiply(BigDecimal(period - 1)).add(gains[i]))
                .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)
            avgLoss = (avgLoss.multiply(BigDecimal(period - 1)).add(losses[i]))
                .divide(BigDecimal(period), 10, java.math.RoundingMode.HALF_UP)

            val rsi = if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal(100)
            } else {
                val rs = avgGain.divide(avgLoss, 10, java.math.RoundingMode.HALF_UP)
                BigDecimal(100).subtract(BigDecimal(100).divide(BigDecimal.ONE.add(rs), 10, java.math.RoundingMode.HALF_UP))
            }
            result.add(rsi)
        }

        return result
    }
}

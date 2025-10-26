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
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

@Service
class OkxApiService(
    private val okxProperties: OkxProperties,
    private val restTemplate: RestTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    // Default start time for tracking minutes
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var invocationCount: Long = 0L

    fun fetchMarketData(symbols: List<String>): Map<String, CurrencyMarketData> {
        invocationCount++
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

        // Calculate technical indicators
        val ema20 = calculateEMA(prices, 20)
        val macd = calculateMACD(prices)
        val rsi7 = calculateRSI(prices, 7)
        val rsi14 = calculateRSI(prices, 14)

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
            if (volumes.isNotEmpty()) volumes.fold(BigDecimal.ZERO, BigDecimal::add) / BigDecimal(volumes.size) else BigDecimal.ZERO
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
            intradayEma20 = prices.let { (0..it.lastIndex).map { i -> calculateEMA(it.subList(0, i + 1), 20) } },
            intradayMacd = prices.let { (0..it.lastIndex).map { i -> calculateMACD(it.subList(0, i + 1)) } },
            intradayRsi7 = prices.let { (0..it.lastIndex).map { i -> calculateRSI(it.subList(0, i + 1), 7) } },
            intradayRsi14 = prices.let { (0..it.lastIndex).map { i -> calculateRSI(it.subList(0, i + 1), 14) } },
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

    fun getSessionStartTime(): Long = sessionStartTime
    fun getInvocationCount(): Long = invocationCount

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
            
            candleArray.map { candle ->
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
        // Format timestamp in ISO 8601 with 3 decimal places for milliseconds
        val instant = Instant.now()
        val timestamp = instant.toString().replace("Z", "").let { 
            val parts = it.split(".")
            if (parts.size == 2) {
                "${parts[0]}.${parts[1].take(3)}Z"
            } else {
                "$it.000Z"
            }
        }
        
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
        if (prices.size < period) return prices.fold(BigDecimal.ZERO, BigDecimal::add) / BigDecimal(prices.size)

        val k = BigDecimal(2).divide(BigDecimal(period + 1), 10, java.math.RoundingMode.HALF_UP)
        var ema = prices.take(period).fold(BigDecimal.ZERO, BigDecimal::add) / BigDecimal(period)

        for (i in period until prices.size) {
            ema = prices[i].multiply(k).add(ema.multiply(BigDecimal.ONE - k))
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
        val losses = changes.map { if (it < BigDecimal.ZERO) -it else BigDecimal.ZERO }

        val avgGain = gains.takeLast(period).fold(BigDecimal.ZERO, BigDecimal::add) / BigDecimal(period)
        val avgLoss = losses.takeLast(period).fold(BigDecimal.ZERO, BigDecimal::add) / BigDecimal(period)

        // Use compareTo instead of == for BigDecimal comparison to handle scale differences
        return if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal(100)
        } else {
            val rs = avgGain / avgLoss
            BigDecimal(100) - (BigDecimal(100) / (BigDecimal.ONE.add(rs)))
        }
    }

    private fun calculateATR(candles: List<OkxCandleResponse>, period: Int): BigDecimal {
        if (candles.isEmpty() || period <= 0) return BigDecimal.ZERO

        val trues = candles.mapNotNull { candle ->
            val high = candle.high.toBigDecimalOrNull() ?: return@mapNotNull null
            val low = candle.low.toBigDecimalOrNull() ?: return@mapNotNull null
            val close = candle.close.toBigDecimalOrNull() ?: return@mapNotNull null
            
            maxOf(
                high - low,
                (high - close).abs(),
                (low - close).abs()
            )
        }

        return if (trues.size < period) {
            if (trues.isNotEmpty()) trues.fold(BigDecimal.ZERO, BigDecimal::add) / BigDecimal(trues.size) else BigDecimal.ZERO
        } else {
            trues.takeLast(period).fold(BigDecimal.ZERO, BigDecimal::add) / BigDecimal(period)
        }
    }
}

# OKX API Integration Guide

## Overview

This document explains how to properly integrate the OKX API into the `OkxApiService` class to fetch real market data.

## Current Status

The `OkxApiService.kt` currently has:
- ✅ Structure and method signatures
- ✅ Technical indicator calculations (EMA, MACD, RSI, ATR)
- ✅ Data models and mappings
- ❌ Actual OKX API calls (placeholders only)

## Required Changes

### 1. Update build.gradle.kts Dependencies

The build.gradle.kts already includes:
```kotlin
implementation("com.okx.open:okx-java-sdk-okx:5.2.18")
implementation("com.squareup.okhttp3:okhttp:4.11.0")
```

### 2. OKX API Endpoints Reference

#### Public Endpoints (No Authentication Required)

**Get Ticker Data**
```
GET /api/v5/market/ticker?instId={instId}
Example: /api/v5/market/ticker?instId=BTC-USDT-SWAP

Response:
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "instType": "SWAP",
      "instId": "BTC-USDT-SWAP",
      "last": "42000.5",
      "askPx": "42001.0",
      "bidPx": "42000.0",
      "open24h": "41000.0",
      "high24h": "43000.0",
      "low24h": "40000.0",
      "volCcy24h": "1000000",
      "vol24h": "25",
      "ts": "1628600975000"
    }
  ]
}
```

**Get Candles**
```
GET /api/v5/market/candles?instId={instId}&bar={bar}&limit={limit}
Example: /api/v5/market/candles?instId=BTC-USDT-SWAP&bar=3m&limit=10

Bar options: 1m, 3m, 5m, 15m, 30m, 1H, 2H, 4H, 6H, 12H, 1D, 1W, 1M, 3M

Response: [
  ["1628600975000", "41000.0", "42000.0", "40000.0", "41500.0", "25", "1000000", "1"]
  // [timestamp, open, high, low, close, volume, volCcy, confirm]
]
```

**Get Funding Rate**
```
GET /api/v5/public/funding-rate?instId={instId}
Example: /api/v5/public/funding-rate?instId=BTC-USDT-SWAP

Response:
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "instId": "BTC-USDT-SWAP",
      "fundingRate": "0.00025",
      "nextFundingTime": "1628601600000",
      "ts": "1628596800000"
    }
  ]
}
```

**Get Open Interest**
```
GET /api/v5/public/open-interest?instId={instId}

Response:
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "instId": "BTC-USDT-SWAP",
      "oi": "500000",
      "oiCcy": "25",
      "ts": "1628600975000"
    }
  ]
}
```

#### Private Endpoints (Require Authentication)

**Get Account Information**
```
GET /api/v5/account/account-info
Headers:
  Authorization: Bearer {token}
  Content-Type: application/json

Response:
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "uid": "12345",
      "upl": "100.50",
      "totalEq": "10500.50",
      "isoEq": "0",
      "adjEq": "10500.50",
      "ordFrz": "0",
      "imr": "0",
      "mmr": "0",
      "mgnRatio": "100",
      "notionalUsd": "0",
      "mgnMode": "cross",
      "posMode": "long_short",
      "autoLoan": false,
      "details": [
        {
          "ccy": "USDT",
          "cashBal": "10500.50",
          "uTime": "1628600975000"
        }
      ]
    }
  ]
}
```

**Get Positions**
```
GET /api/v5/account/positions?instType={instType}

Response:
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "posId": "123456",
      "tradeId": "789",
      "instId": "BTC-USDT-SWAP",
      "instType": "SWAP",
      "mgnMode": "isolated",
      "posSide": "long",
      "pos": "1",
      "baseBal": "0",
      "baseBorrow": "0",
      "baseBorrowAmount": "0",
      "quoteBal": "0",
      "quoteBorrow": "0",
      "quoteBorrowAmount": "0",
      "posCcy": "",
      "avgPx": "42000.0",
      "markPx": "42100.0",
      "liquidationPx": "41000.0",
      "lever": "25",
      "mgnRatio": "0.5",
      "imr": "0",
      "mmr": "0",
      "upl": "100",
      "uplRatio": "0.01",
      "tradeVol": "42000",
      "fee": "-100",
      "fundingBal": "0",
      "fundingTime": "0",
      "cTime": "1628600975000",
      "uTime": "1628600975000"
    }
  ]
}
```

### 3. Implementation Examples

#### Using RestTemplate (Simpler)

```kotlin
// In OkxApiService.kt

private fun fetchTickerWithAuth(instId: String): OkxTickerResponse? {
    return try {
        val url = "${okxProperties.baseUrl}/api/v5/market/ticker?instId=$instId"
        val response = restTemplate.getForObject(url, String::class.java)
        
        // Parse JSON using Jackson
        val objectMapper = ObjectMapper()
        val jsonNode = objectMapper.readTree(response)
        val data = jsonNode.get("data")?.get(0)
        
        OkxTickerResponse(
            instrumentId = data?.get("instId")?.asText() ?: "",
            lastPrice = data?.get("last")?.asText() ?: "0",
            askPrice = data?.get("askPx")?.asText() ?: "0",
            bidPrice = data?.get("bidPx")?.asText() ?: "0",
            timestamp = data?.get("ts")?.asText() ?: ""
        )
    } catch (e: Exception) {
        log.error("Error fetching ticker for $instId", e)
        null
    }
}

private fun fetchCandlesWithAuth(instId: String, period: String, limit: Int): List<OkxCandleResponse> {
    return try {
        val url = "${okxProperties.baseUrl}/api/v5/market/candles?instId=$instId&bar=$period&limit=$limit"
        val response = restTemplate.getForObject(url, String::class.java)
        
        val objectMapper = ObjectMapper()
        val jsonNode = objectMapper.readTree(response)
        val candleArray = jsonNode.get("data")
        
        candleArray?.map { candle ->
            val values = candle.map { it.asText() }
            OkxCandleResponse(
                timestamp = values[0],
                open = values[1],
                high = values[2],
                low = values[3],
                close = values[4],
                volume = values[5],
                volumeCcy = values[6]
            )
        } ?: emptyList()
    } catch (e: Exception) {
        log.error("Error fetching candles for $instId", e)
        emptyList()
    }
}

private fun fetchAccountWithAuth(): OkxAccountResponse {
    return try {
        val url = "${okxProperties.baseUrl}/api/v5/account/account-info"
        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer ${generateAuthToken()}")
            contentType = MediaType.APPLICATION_JSON
        }
        
        val entity = HttpEntity<String>(headers)
        val response = restTemplate.exchange(url, HttpMethod.GET, entity, String::class.java)
        
        val objectMapper = ObjectMapper()
        val jsonNode = objectMapper.readTree(response.body)
        val data = jsonNode.get("data")?.get(0)
        
        OkxAccountResponse(
            totalEquity = data?.get("totalEq")?.asText() ?: "0",
            availableBalance = data?.get("adjEq")?.asText() ?: "0",
            cashBalance = data?.get("cashBal")?.asText() ?: "0",
            unrealizedPnl = data?.get("upl")?.asText() ?: "0"
        )
    } catch (e: Exception) {
        log.error("Error fetching account info", e)
        OkxAccountResponse(
            totalEquity = "0",
            availableBalance = "0",
            cashBalance = "0",
            unrealizedPnl = "0"
        )
    }
}
```

#### Authentication (HMAC-SHA256)

OKX uses HMAC-SHA256 for request signing:

```kotlin
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.time.Instant

private fun generateAuthToken(): String {
    val timestamp = Instant.now().epochSecond.toString()
    val message = "$timestamp${HttpMethod.GET}/api/v5/account/account-info"
    
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(okxProperties.secret.toByteArray(), "HmacSHA256")
    mac.init(secretKey)
    
    val signature = Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray()))
    
    return signature
}

// Add headers for authenticated requests:
val headers = HttpHeaders().apply {
    set("OK-ACCESS-KEY", okxProperties.key)
    set("OK-ACCESS-SIGN", generateSignature(timestamp, method, requestPath, body))
    set("OK-ACCESS-TIMESTAMP", timestamp)
    set("OK-ACCESS-PASSPHRASE", okxProperties.passphrase)
}
```

### 4. Complete Implementation Template

Replace the placeholder methods in `OkxApiService.kt`:

```kotlin
// Add Jackson ObjectMapper as a field
private val objectMapper = ObjectMapper()

// Replace all placeholder API methods with actual implementations similar to examples above
```

### 5. Error Handling Best Practices

```kotlin
private fun <T> executeApiCall(block: () -> T): T? {
    return try {
        block()
    } catch (e: HttpClientErrorException) {
        log.error("HTTP ${e.statusCode}: ${e.message}", e)
        null
    } catch (e: HttpServerErrorException) {
        log.error("Server error: ${e.message}", e)
        null
    } catch (e: ResourceAccessException) {
        log.error("Connection error: ${e.message}", e)
        null
    } catch (e: Exception) {
        log.error("Unexpected error: ${e.message}", e)
        null
    }
}
```

### 6. Rate Limiting

OKX API has rate limits. Add retry logic:

```kotlin
private fun <T> executeWithRetry(maxRetries: Int = 3, block: () -> T?): T? {
    var lastException: Exception? = null
    
    for (attempt in 1..maxRetries) {
        try {
            val result = block()
            if (result != null) return result
            
            if (attempt < maxRetries) {
                Thread.sleep(100 * attempt) // Exponential backoff
            }
        } catch (e: Exception) {
            lastException = e
            if (attempt < maxRetries) {
                Thread.sleep(100 * attempt)
            }
        }
    }
    
    log.error("Failed after $maxRetries attempts", lastException)
    return null
}
```

## Testing

```kotlin
@SpringBootTest
class OkxApiServiceTest {
    
    @Autowired
    lateinit var okxApiService: OkxApiService
    
    @Test
    fun testFetchMarketData() {
        val data = okxApiService.fetchMarketData(listOf("BTC", "ETH"))
        assertNotNull(data["BTC"])
        assertNotNull(data["ETH"])
    }
    
    @Test
    fun testFetchAccountInfo() {
        val account = okxApiService.fetchAccountInfo()
        assertTrue(account.accountValue.compareTo(BigDecimal.ZERO) >= 0)
    }
}
```

## References

- OKX API Documentation: https://www.okx.com/docs-v5/en/
- OKX Java SDK: https://github.com/okx/okx-java-sdk-okx
- REST API Best Practices: https://www.okx.com/docs-v5/en/#overview-rate-limit

## Support

For OKX-specific questions:
1. Check the official OKX API documentation
2. Verify API key permissions
3. Test endpoints with curl/Postman first
4. Check OKX status page for service issues
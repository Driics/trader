# Quick Start Guide

## 1. Install & Configure

### Step 1: Set API Credentials
Update `src/main/resources/application.properties`:
```properties
okx.api.key=YOUR_OKX_API_KEY
okx.api.secret=YOUR_OKX_API_SECRET
okx.api.passphrase=YOUR_OKX_PASSPHRASE
```

### Step 2: Build
```powershell
Set-Location "E:\LeetCode\aiTrader"
gradle clean build
```

### Step 3: Run
```powershell
gradle bootRun
```

## 2. Testing the Application

### Check Health
```bash
curl http://localhost:8080/api/prompt/health
```

### Trigger Manual Update
```bash
curl -X POST http://localhost:8080/api/prompt/update
```

### View Generated Prompt
The prompt will be written to `./prompt.txt` and printed to console.

## 3. Scheduled Execution

The application will automatically:
- ✅ Run every 3 minutes
- ✅ Fetch market data from OKX
- ✅ Calculate technical indicators
- ✅ Generate prompt.txt
- ✅ Print to console

## 4. Integrating Real OKX API

The current implementation has placeholder API calls. To integrate the actual OKX SDK:

### A. Update Dependencies (already added to build.gradle.kts)
```kotlin
implementation("com.okx.open:okx-java-sdk-okx:5.2.18")
```

### B. Implement OKX API Client in `OkxApiService.kt`

Replace placeholder methods with actual OKX SDK calls:

```kotlin
import com.okx.open.api.bean.market.*
import com.okx.open.api.bean.account.*
import com.okx.open.api.client.*

// Example for fetching ticker data:
private fun fetchTickerReal(instId: String): OkxTickerResponse? {
    return try {
        val marketClient = MarketClient.newInstance(okxProperties)
        val ticker = marketClient.getTickers(instId)
        // Parse response and return
        null
    } catch (e: Exception) {
        log.error("Error fetching ticker", e)
        null
    }
}
```

### C. API Methods to Implement

1. **Market Data Endpoints**
   - `GET /api/v5/market/candles` - Historical candles
   - `GET /api/v5/market/ticker` - Current ticker
   - `GET /api/v5/market/funding-rate` - Funding rates
   - `GET /api/v5/public/open-interest` - Open interest

2. **Account Endpoints** (Requires API Key)
   - `GET /api/v5/account/account-info` - Account information
   - `GET /api/v5/account/positions` - Open positions

3. **Orders Endpoints** (Optional)
   - `GET /api/v5/trade/orders-pending` - Pending orders

### D. Example Implementation

```kotlin
// In OkxApiService.kt

private fun fetchTickerReal(instId: String): OkxTickerResponse? {
    val url = "${okxProperties.baseUrl}/api/v5/market/ticker?instId=$instId"
    return try {
        val response = restTemplate.getForObject(url, String::class.java)
        // Parse JSON response using Jackson
        null
    } catch (e: Exception) {
        log.error("Error fetching ticker for $instId", e)
        null
    }
}

private fun fetchCandlesReal(instId: String, period: String, limit: Int): List<OkxCandleResponse> {
    val url = "${okxProperties.baseUrl}/api/v5/market/candles?instId=$instId&bar=$period&limit=$limit"
    return try {
        // Implementation using RestTemplate or WebClient
        emptyList()
    } catch (e: Exception) {
        log.error("Error fetching candles for $instId", e)
        emptyList()
    }
}
```

## 5. Project Architecture

```
┌─────────────────────────────────────┐
│   Spring Boot Application           │
│   (AiTraderApplication.kt)          │
└──────────┬──────────────────────────┘
           │
    ┌──────┴─────┐
    │             │
┌───▼────┐   ┌──▼──────────┐
│ Scheduler   │ REST API    │
│ (Every 3min)│ (Manual Ctrl)│
└───┬────┘   └──┬──────────┘
    │           │
    └─────┬─────┘
          │
    ┌─────▼─────────────────┐
    │ PromptSchedulerService│
    └─────┬─────────────────┘
          │
    ┌─────▼──────────────────┐
    │ OkxApiService          │
    │ (Fetch Data)           │
    └─────┬──────────────────┘
          │
    ┌─────▼──────────────────────┐
    │ OKX API                    │
    │ Market Data & Account Info │
    └─────┬──────────────────────┘
          │
    ┌─────▼───────────────────┐
    │ PromptBuilderService    │
    │ (Format Output)         │
    └─────┬───────────────────┘
          │
    ┌─────┴─────────┐
    │               │
┌───▼────┐    ┌────▼─────┐
│ Console│    │ prompt.txt│
│ Output │    │   File    │
└────────┘    └───────────┘
```

## 6. Environment Variable Configuration

For production, use environment variables instead of hardcoded credentials:

```powershell
# Windows PowerShell
$env:OKX_API_KEY = "your_key"
$env:OKX_API_SECRET = "your_secret"
$env:OKX_API_PASSPHRASE = "your_passphrase"
```

Then update `application.properties` to use env vars:
```properties
okx.api.key=${OKX_API_KEY}
okx.api.secret=${OKX_API_SECRET}
okx.api.passphrase=${OKX_API_PASSPHRASE}
```

## 7. Logs and Debugging

### View Application Logs
The application logs to console with package `ru.driics.aitrade` prefix.

### Enable Debug Logging
Add to `application.properties`:
```properties
logging.level.ru.driics.aitrade=DEBUG
logging.level.org.springframework.web=DEBUG
```

## 8. Customization

### Change Update Frequency
In `PromptSchedulerService.kt`, modify the `@Scheduled` annotation:
```kotlin
@Scheduled(fixedDelay = 60000) // Every 1 minute
@Scheduled(fixedDelay = 300000) // Every 5 minutes
```

### Add/Remove Currencies
In `application.properties`:
```properties
trading.currencies=BTC,ETH,SOL  # Only these three
```

### Change Output Path
In `application.properties`:
```properties
prompt.output-path=C:/output/prompt.txt
```

## 9. Troubleshooting

### Connection Refused
- Check if server is running: `curl http://localhost:8080/api/prompt/health`
- Check port: `netstat -ano | findstr :8080`

### API Authentication Failed
- Verify credentials in `application.properties`
- Check OKX API key permissions
- Ensure API key is active in OKX console

### File Not Found
- Check write permissions to output directory
- Verify path exists: `Test-Path "E:/LeetCode/aiTrader"`

## 10. Next Steps

1. ✅ Configure OKX credentials
2. ✅ Build and run application
3. ✅ Test endpoints with curl or Postman
4. ✅ Verify prompt.txt is generated
5. ✅ Implement real OKX API integration
6. ✅ Deploy to production

Need help? Check the full documentation in `SETUP.md`
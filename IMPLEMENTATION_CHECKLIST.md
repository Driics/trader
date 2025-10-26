# Implementation Checklist

## Phase 1: Environment Setup ✅ COMPLETED

- [x] Spring Boot 3.5.7 + Kotlin 1.9.25 configured
- [x] Java 21 target set
- [x] Gradle build system setup
- [x] All dependencies added:
  - Spring Web + WebFlux
  - Jackson Kotlin module
  - OKX SDK (5.2.18)
  - OkHttp client
  - Logging framework

## Phase 2: Code Structure ✅ COMPLETED

- [x] Application configuration
  - [x] `AiTraderApplication.kt` - Main Spring app with @EnableScheduling
  - [x] `OkxProperties.kt` - API credentials config
  - [x] `TradingProperties.kt` - Trading parameters
  - [x] `PromptProperties.kt` - Output configuration
  - [x] `RestClientConfig.kt` - HTTP client beans

- [x] Data models
  - [x] `CurrencyMarketData` - Market data structure
  - [x] `AccountInfo` - Account metrics
  - [x] `Position` - Trading positions
  - [x] `MarketState` - Aggregated snapshot
  - [x] OKX API response models

- [x] Services
  - [x] `OkxApiService.kt` - API client (structure ready, needs implementation)
  - [x] `PromptBuilderService.kt` - Prompt generation
  - [x] `PromptSchedulerService.kt` - Scheduling & orchestration

- [x] REST Controller
  - [x] `PromptController.kt` - Health, status, update endpoints

## Phase 3: Configuration ⏳ IN PROGRESS

- [ ] **CRITICAL:** Update OKX API credentials
  ```
  File: src/main/resources/application.properties
  Update these values:
  - okx.api.key=YOUR_ACTUAL_KEY
  - okx.api.secret=YOUR_ACTUAL_SECRET
  - okx.api.passphrase=YOUR_ACTUAL_PASSPHRASE
  ```

- [ ] Verify output directory (default: `./prompt.txt`)
  - [ ] Create directory if needed
  - [ ] Ensure write permissions

- [ ] Test properties file
  ```bash
  gradle bootRun  # Should not have property errors
  ```

## Phase 4: API Integration 🔴 NOT STARTED

### 4.1 Public API Endpoints (No Auth Required)

These can be tested first:

- [ ] `fetchTicker()` method
  - [ ] Endpoint: GET /api/v5/market/ticker?instId={instId}
  - [ ] Implementation template provided in OKX_API_INTEGRATION.md
  - [ ] Test with curl first:
    ```bash
    curl "https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT-SWAP"
    ```

- [ ] `fetchCandles()` method
  - [ ] Endpoint: GET /api/v5/market/candles
  - [ ] Parameters: instId, bar (3m/5m/1H/4h), limit
  - [ ] Test with curl:
    ```bash
    curl "https://www.okx.com/api/v5/market/candles?instId=BTC-USDT-SWAP&bar=3m&limit=10"
    ```

- [ ] `fetchFundingRate()` method
  - [ ] Endpoint: GET /api/v5/public/funding-rate
  - [ ] Test with curl

- [ ] `fetchOpenInterest()` method
  - [ ] Endpoint: GET /api/v5/public/open-interest
  - [ ] Test with curl

### 4.2 Private API Endpoints (Auth Required)

These require HMAC-SHA256 signing:

- [ ] Implement HMAC-SHA256 authentication helper method
  - [ ] Generate timestamp
  - [ ] Create signature string (timestamp + method + path)
  - [ ] Sign with secret key
  - [ ] Add auth headers:
    - OK-ACCESS-KEY
    - OK-ACCESS-SIGN
    - OK-ACCESS-TIMESTAMP
    - OK-ACCESS-PASSPHRASE

- [ ] `fetchAccount()` method
  - [ ] Endpoint: GET /api/v5/account/account-info
  - [ ] Requires authentication
  - [ ] Parse totalEq, availBal, cashBal

- [ ] `fetchOpenPositions()` method
  - [ ] Endpoint: GET /api/v5/account/positions
  - [ ] Requires authentication
  - [ ] Parse position data

## Phase 5: Testing 🟡 READY

### 5.1 Unit Tests

- [ ] Create test class `OkxApiServiceTest`
- [ ] Test technical indicator calculations:
  - [ ] EMA calculation
  - [ ] MACD calculation
  - [ ] RSI calculation
  - [ ] ATR calculation
- [ ] Mock OKX API responses
- [ ] Test data model mapping

### 5.2 Integration Tests

- [ ] Test with actual OKX API (use test/testnet if available)
- [ ] Verify candle data parsing
- [ ] Verify ticker data parsing
- [ ] Verify account info retrieval
- [ ] Verify position retrieval

### 5.3 Manual Testing

- [ ] Start application: `gradle bootRun`
- [ ] Check health: `curl http://localhost:8080/api/prompt/health`
- [ ] Check status: `curl http://localhost:8080/api/prompt/status`
- [ ] Trigger update: `curl -X POST http://localhost:8080/api/prompt/update`
- [ ] Verify prompt.txt file created
- [ ] Verify console output
- [ ] Wait 3 minutes, verify auto-update occurs

## Phase 6: Optimization 🟢 OPTIONAL

- [ ] Add caching layer
  - [ ] Cache market data for 1 minute
  - [ ] Reduce API calls
  - [ ] Implement cache invalidation

- [ ] Add error handling & retry logic
  - [ ] Exponential backoff (100ms, 200ms, 400ms, etc.)
  - [ ] Circuit breaker pattern
  - [ ] Fallback strategies

- [ ] Add monitoring & metrics
  - [ ] Micrometer metrics
  - [ ] API call counters
  - [ ] Response time tracking
  - [ ] Success/failure rates

- [ ] Add logging improvements
  - [ ] Request/response logging
  - [ ] Performance timing logs
  - [ ] Detailed error context

## Phase 7: Production Ready 🟢 OPTIONAL

- [ ] Environment variable configuration
  ```powershell
  $env:OKX_API_KEY = "your_key"
  $env:OKX_API_SECRET = "your_secret"
  $env:OKX_API_PASSPHRASE = "your_passphrase"
  ```

- [ ] Docker configuration (optional)
  - [ ] Create Dockerfile
  - [ ] Docker-compose for deployment

- [ ] Deployment guide
  - [ ] Kubernetes manifests (if needed)
  - [ ] Cloud platform deployment

- [ ] Documentation
  - [ ] API documentation
  - [ ] Architecture guide
  - [ ] Troubleshooting guide

## Implementation Order Recommendation

```
1. Update OKX credentials in application.properties
   └─> gradle clean build (verify no errors)

2. Implement public API methods first (easier, no auth needed)
   └─> fetchTicker()
   └─> fetchCandles()
   └─> fetchFundingRate()
   └─> fetchOpenInterest()

3. Test each public method with curl before implementation
   └─> Verify API responses match documentation

4. Implement authentication
   └─> HMAC-SHA256 signing method
   └─> Auth headers helper

5. Implement private API methods
   └─> fetchAccount()
   └─> fetchOpenPositions()

6. Test full integration
   └─> gradle bootRun
   └─> Test all REST endpoints
   └─> Verify prompt.txt generation

7. Verify scheduled execution
   └─> Wait 3 minutes for automatic update
   └─> Check prompt.txt timestamp changes

8. Optional: Add monitoring, caching, error handling
```

## Common Issues & Solutions

### Issue: "Cannot read property 'data' of null"
**Solution:** API endpoint returned different structure. Check OKX API docs and response format.

### Issue: "401 Unauthorized"
**Solution:** Authentication headers missing or invalid. Verify HMAC-SHA256 signature.

### Issue: "Rate limit exceeded"
**Solution:** Add retry logic with exponential backoff. Space out API calls.

### Issue: "File not found when writing prompt.txt"
**Solution:** Create output directory. Verify path exists and permissions are correct.

### Issue: "RestTemplate bean not found"
**Solution:** Check `RestClientConfig.kt` is scanned. Verify @Configuration annotation present.

## Testing Commands

```bash
# Build application
gradle clean build

# Run application
gradle bootRun

# Test health endpoint
curl http://localhost:8080/api/prompt/health

# Test status endpoint
curl http://localhost:8080/api/prompt/status

# Trigger manual update
curl -X POST http://localhost:8080/api/prompt/update

# View generated prompt
cat prompt.txt  # Windows: Get-Content prompt.txt

# Check if file is being updated (run twice with 3 min gap)
ls -la prompt.txt  # Windows: Get-Item prompt.txt | Select-Object LastWriteTime
```

## Progress Tracking

| Phase | Status | Notes |
|-------|--------|-------|
| Environment Setup | ✅ Complete | All dependencies configured |
| Code Structure | ✅ Complete | Services, models, controllers ready |
| Configuration | ⏳ In Progress | Awaiting OKX credentials |
| API Integration | 🔴 Not Started | Ready for implementation |
| Testing | 🟡 Ready | Can begin once API implemented |
| Optimization | 🟢 Optional | After core functionality works |
| Production | 🟢 Optional | After testing complete |

## Key Files Reference

| File | Purpose | Status |
|------|---------|--------|
| `AiTraderApplication.kt` | Main app | ✅ Ready |
| `OkxApiService.kt` | API client | ⏳ Needs implementation |
| `PromptBuilderService.kt` | Prompt generation | ✅ Complete |
| `PromptSchedulerService.kt` | Scheduling | ✅ Complete |
| `PromptController.kt` | REST endpoints | ✅ Complete |
| `application.properties` | Configuration | ⏳ Needs credentials |
| `build.gradle.kts` | Dependencies | ✅ Complete |

## Next Step

👉 **Update `src/main/resources/application.properties` with your OKX API credentials**

Then proceed to Phase 4: API Integration using the guidance in `OKX_API_INTEGRATION.md`

---

**Need help?** Refer to:
- `SETUP.md` - Full setup instructions
- `QUICKSTART.md` - Quick start guide
- `OKX_API_INTEGRATION.md` - Detailed API integration examples
- `PROJECT_SUMMARY.md` - Architecture overview
# AI Trader Architecture Guide

## System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                   External Systems                              │
│  ┌─────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │  OKX API    │    │ Your Trading │    │   Console/   │       │
│  │   Server    │    │   Database   │    │  File System │       │
│  └────▲────────┘    └──────▲───────┘    └──────▲───────┘       │
│       │                     │                    │              │
└───────┼─────────────────────┼────────────────────┼──────────────┘
        │                     │                    │
        │                     │                    │
┌───────┼─────────────────────┼────────────────────┼──────────────┐
│       │                     │                    │              │
│   ┌───▼─────────────────────▼────────────────────▼────┐        │
│   │                                                     │        │
│   │    Spring Boot Application (Port 8080)             │        │
│   │                                                     │        │
│   └─────────────────┬───────────────────────────────────┘        │
│                     │                                           │
│   ┌─────────────────┴────────────────────────────────────┐      │
│   │                                                      │      │
│   │  REST Layer (PromptController)                       │      │
│   │  ┌─────────────────────────────────────────────────┐ │      │
│   │  │  GET  /api/prompt/health    - Health check    │ │      │
│   │  │  GET  /api/prompt/status    - Service status  │ │      │
│   │  │  POST /api/prompt/update    - Manual trigger  │ │      │
│   │  └─────────────────────────────────────────────────┘ │      │
│   │                                                      │      │
│   └──────────────────┬─────────────────────────────────┘       │
│                      │                                         │
│   ┌──────────────────▼─────────────────────────────────┐       │
│   │                                                    │       │
│   │  Business Logic Layer                             │       │
│   │                                                    │       │
│   │  ┌────────────────────────────────────────────┐   │       │
│   │  │  PromptSchedulerService                    │   │       │
│   │  │  • Scheduled every 3 minutes               │   │       │
│   │  │  • Orchestrates data fetching              │   │       │
│   │  │  • Triggers prompt generation              │   │       │
│   │  │  • Manages console/file output             │   │       │
│   │  └──────────────────┬─────────────────────────┘   │       │
│   │                     │                              │       │
│   │  ┌──────────────────▼─────────────────────────┐   │       │
│   │  │  OkxApiService                             │   │       │
│   │  │  • Fetches market data                     │   │       │
│   │  │  • Retrieves account info                  │   │       │
│   │  │  • Gets open positions                     │   │       │
│   │  │  • Calculates technical indicators         │   │       │
│   │  │  • Tracks session metrics                  │   │       │
│   │  │                                            │   │       │
│   │  │  Methods (to be implemented):              │   │       │
│   │  │  • fetchTicker()                           │   │       │
│   │  │  • fetchCandles()                          │   │       │
│   │  │  • fetchFundingRate()                      │   │       │
│   │  │  • fetchOpenInterest()                     │   │       │
│   │  │  • fetchAccount()                          │   │       │
│   │  │  • fetchOpenPositions()                    │   │       │
│   │  │                                            │   │       │
│   │  │  Calculations (implemented):               │   │       │
│   │  │  • calculateEMA()                          │   │       │
│   │  │  • calculateMACD()                         │   │       │
│   │  │  • calculateRSI()                          │   │       │
│   │  │  • calculateATR()                          │   │       │
│   │  └──────────────────┬─────────────────────────┘   │       │
│   │                     │                              │       │
│   │  ┌──────────────────▼─────────────────────────┐   │       │
│   │  │  PromptBuilderService                      │   │       │
│   │  │  • Builds formatted prompt                 │   │       │
│   │  │  • Formats numbers appropriately           │   │       │
│   │  │  • Writes to file                          │   │       │
│   │  │  • Prints to console                       │   │       │
│   │  │  • Generates market state snapshots        │   │       │
│   │  └──────────────────┬─────────────────────────┘   │       │
│   │                     │                              │       │
│   └─────────────────────┼──────────────────────────────┘       │
│                         │                                      │
│   ┌─────────────────────▼──────────────────────────┐           │
│   │                                                │           │
│   │  Data Layer                                    │           │
│   │                                                │           │
│   │  Models:                                       │           │
│   │  • CurrencyMarketData                          │           │
│   │  • AccountInfo                                 │           │
│   │  • Position                                    │           │
│   │  • MarketState                                 │           │
│   │  • OkxCandleResponse                           │           │
│   │  • OkxTickerResponse                           │           │
│   │  • OkxPositionResponse                         │           │
│   │  • OkxAccountResponse                          │           │
│   │                                                │           │
│   └─────────────────────────────────────────────────┘           │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

## Data Flow Diagram

### Complete Cycle (Every 3 Minutes)

```
[START: Scheduled Task Triggered]
    ↓
[PromptSchedulerService.updatePrompt()]
    ↓
    ├─→ [OkxApiService.fetchMarketData(currencies)]
    │    ├─→ [fetchCurrencyData("BTC")]
    │    │    ├─→ fetchTicker(instId)
    │    │    ├─→ fetchCandles(instId, "3m", 10)
    │    │    ├─→ fetchCandles(instId, "4h", 10)
    │    │    ├─→ calculateEMA(prices, 20)
    │    │    ├─→ calculateMACD(prices)
    │    │    ├─→ calculateRSI(prices, 7)
    │    │    ├─→ calculateRSI(prices, 14)
    │    │    ├─→ fetchFundingRate(instId)
    │    │    └─→ fetchOpenInterest(instId)
    │    │
    │    ├─→ [fetchCurrencyData("ETH")]
    │    ├─→ [fetchCurrencyData("SOL")]
    │    ├─→ [fetchCurrencyData("BNB")]
    │    ├─→ [fetchCurrencyData("XRP")]
    │    └─→ [fetchCurrencyData("DOGE")]
    │
    ├─→ [OkxApiService.fetchAccountInfo()]
    │    └─→ fetchAccount() → Parse totalEq, availBal, cashBal
    │
    ├─→ [OkxApiService.fetchPositions()]
    │    └─→ fetchOpenPositions() → Parse active positions
    │
    ├─→ [Build MarketState Object]
    │    {
    │      timestamp: Long,
    │      minutesSinceStart: Long,
    │      invocationCount: Long,
    │      currencies: {BTC, ETH, SOL, BNB, XRP, DOGE},
    │      account: {totalReturn, availableCash, accountValue},
    │      positions: [position1, position2, ...]
    │    }
    │
    ├─→ [PromptBuilderService.buildPrompt(marketState)]
    │    └─→ Format all data into prompt.txt format
    │
    ├─→ [PromptBuilderService.writePromptToFile(prompt)]
    │    └─→ Write prompt.txt to disk
    │
    ├─→ [PromptBuilderService.printPromptToConsole(prompt)]
    │    └─→ Print to console output
    │
[END: Wait 3 minutes, repeat]
```

## Component Interaction Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    PromptSchedulerService                   │
│  • Triggered by Spring @Scheduled every 180 seconds         │
│  • Orchestrates entire data fetching and formatting         │
└────┬──────────────────────────────────────────────────────┬─┘
     │                                                        │
     │ calls                                    calls         │
     ↓                                                        ↓
┌──────────────────────┐                      ┌──────────────────────┐
│   OkxApiService      │                      │ PromptBuilderService │
│  • Fetches raw data  │                      │  • Formats output    │
│  • Calculates       │◄──── returns ────────┤  • Creates prompt    │
│    indicators       │   market data        │  • Writes files      │
│  • Parses responses │                      │  • Prints console    │
└──────────────────────┘                      └──────────────────────┘
     ▲                                                   ▲
     │                                                   │
     │ queries (HTTP REST)                              │
     │                                                   │
  ┌──┴───────────────────────────────────────────────────┘
  │
  │ ┌──────────────────────────────────┐
  │ │  OKX API Server                  │
  │ │  • Market data endpoints         │
  │ │  • Account endpoints             │
  │ │  • Position endpoints            │
  │ └──────────────────────────────────┘
  │
  │ ┌──────────────────────────────────┐
  │ │  Output Destinations             │
  │ └────────┬─────────────────────────┘
  │          │
  ├─────────┤
  │         │
  ▼         ▼
Console   prompt.txt
Output      File
```

## Class Hierarchy & Dependencies

```
AiTraderApplication
│
├─ PromptController
│  └─ PromptSchedulerService
│     ├─ OkxApiService
│     │  ├─ OkxProperties
│     │  ├─ RestTemplate
│     │  └─ Technical Indicators (calculateEMA, calculateMACD, etc.)
│     │
│     └─ PromptBuilderService
│        └─ PromptProperties
│
├─ RestClientConfig
│  └─ RestTemplate (Bean)
│
└─ Configuration Properties
   ├─ OkxProperties
   ├─ TradingProperties
   └─ PromptProperties
```

## Data Models

```
MarketState (Main Data Structure)
│
├─ timestamp: Long
├─ minutesSinceStart: Long
├─ invocationCount: Long
│
├─ currencies: Map<String, CurrencyMarketData>
│  │
│  ├─ CurrencyMarketData (for each: BTC, ETH, SOL, BNB, XRP, DOGE)
│  │  ├─ symbol: String
│  │  ├─ currentPrice: BigDecimal
│  │  ├─ currentEma20: BigDecimal
│  │  ├─ currentMacd: BigDecimal
│  │  ├─ currentRsi7: BigDecimal
│  │  ├─ openInterest: BigDecimal
│  │  ├─ fundingRate: BigDecimal
│  │  ├─ intradayPrices: List<BigDecimal> (10 values, 3-min intervals)
│  │  ├─ intradayEma20: List<BigDecimal>
│  │  ├─ intradayMacd: List<BigDecimal>
│  │  ├─ intradayRsi7: List<BigDecimal>
│  │  ├─ intradayRsi14: List<BigDecimal>
│  │  ├─ ema20_4h: BigDecimal
│  │  ├─ ema50_4h: BigDecimal
│  │  ├─ atr3_4h: BigDecimal
│  │  ├─ atr14_4h: BigDecimal
│  │  ├─ volume4h: BigDecimal
│  │  ├─ avgVolume4h: BigDecimal
│  │  ├─ macd4h: List<BigDecimal>
│  │  └─ rsi14_4h: List<BigDecimal>
│  │
│  └─ (Repeated for all 6 currencies)
│
├─ account: AccountInfo
│  ├─ totalReturn: BigDecimal
│  ├─ availableCash: BigDecimal
│  ├─ accountValue: BigDecimal
│  └─ sharpeRatio: BigDecimal
│
└─ positions: List<Position>
   │
   └─ Position (for each open position)
      ├─ symbol: String
      ├─ quantity: BigDecimal
      ├─ entryPrice: BigDecimal
      ├─ currentPrice: BigDecimal
      ├─ liquidationPrice: BigDecimal
      ├─ unrealizedPnl: BigDecimal
      ├─ leverage: Int
      ├─ exitPlan: ExitPlan
      │  ├─ profitTarget: BigDecimal
      │  ├─ stopLoss: BigDecimal
      │  └─ invalidationCondition: String
      ├─ confidence: BigDecimal
      ├─ riskUsd: BigDecimal
      └─ (Other position metadata)
```

## API Call Sequence (Detailed)

```
t=0min
│
├─ scheduler triggers
├─ PromptSchedulerService.updatePrompt()
│
├─ OkxApiService.fetchMarketData([BTC, ETH, SOL, BNB, XRP, DOGE])
│  │
│  └─ For each currency:
│     ├─ [GET] /api/v5/market/ticker?instId=BTC-USDT-SWAP
│     │   └─ Returns: currentPrice, askPrice, bidPrice
│     │
│     ├─ [GET] /api/v5/market/candles?instId=BTC-USDT-SWAP&bar=3m&limit=10
│     │   └─ Returns: [timestamp, open, high, low, close, volume]
│     │
│     ├─ Calculate EMA(20) from candles
│     ├─ Calculate MACD from candles
│     ├─ Calculate RSI(7) from candles
│     ├─ Calculate RSI(14) from candles
│     │
│     ├─ [GET] /api/v5/market/candles?instId=BTC-USDT-SWAP&bar=4h&limit=10
│     │   └─ For 4-hour indicators
│     │
│     ├─ [GET] /api/v5/public/funding-rate?instId=BTC-USDT-SWAP
│     │   └─ Returns: fundingRate, nextFundingTime
│     │
│     └─ [GET] /api/v5/public/open-interest?instId=BTC-USDT-SWAP
│         └─ Returns: oi (open interest)
│
├─ OkxApiService.fetchAccountInfo()
│  │
│  └─ [GET] /api/v5/account/account-info [SIGNED]
│      └─ Returns: totalEq, availBal, cashBal, upl
│
├─ OkxApiService.fetchPositions()
│  │
│  └─ [GET] /api/v5/account/positions [SIGNED]
│      └─ Returns: [position1, position2, ...]
│         Each position includes: instId, pos, avgPx, markPx, etc.
│
├─ Build MarketState aggregating all data
│
├─ PromptBuilderService.buildPrompt(marketState)
│  └─ Format all data according to prompt.txt template
│
├─ PromptBuilderService.writePromptToFile(prompt)
│  └─ Write formatted prompt to ./prompt.txt
│
├─ PromptBuilderService.printPromptToConsole(prompt)
│  └─ Print formatted prompt to console
│
└─ Wait 180 seconds → repeat
```

## Error Handling Flow

```
Error in any step
│
├─ OkxApiService catches exception
│  ├─ Log error with context
│  ├─ Return null or empty collection
│  └─ Continue processing with fallback data
│
├─ PromptSchedulerService catches exception
│  ├─ Log comprehensive error
│  ├─ Continue (will retry in 3 minutes)
│  └─ Send error response if REST endpoint called
│
└─ Application continues running (resilient)
```

## File I/O Flow

```
PromptBuilderService.writePromptToFile(prompt)
│
├─ Create File object with path from PromptProperties
├─ Create parent directories if needed (mkdirs)
├─ Write prompt string to file
├─ Log success/failure
└─ Return boolean status

PromptBuilderService.printPromptToConsole(prompt)
│
└─ println(prompt) to stdout
```

## Configuration Loading

```
Spring Context Initialization
│
├─ Scan @Configuration classes
│  └─ RestClientConfig
│      └─ Create RestTemplate bean
│
├─ Scan @Component properties classes
│  ├─ OkxProperties
│  ├─ TradingProperties
│  └─ PromptProperties
│     └─ Load from application.properties
│
├─ Scan @Service classes
│  ├─ OkxApiService
│  ├─ PromptBuilderService
│  └─ PromptSchedulerService
│
├─ Scan @RestController classes
│  └─ PromptController
│
├─ Enable @EnableScheduling
│  └─ Schedule @Scheduled methods
│
└─ Application ready
```

## Request/Response Example

### GET /api/prompt/health

**Request:**
```
GET /api/prompt/health HTTP/1.1
Host: localhost:8080
```

**Response:**
```json
{
  "status": "OK",
  "message": "AI Trader is running"
}
```

### POST /api/prompt/update

**Request:**
```
POST /api/prompt/update HTTP/1.1
Host: localhost:8080
```

**Response:**
```json
{
  "status": "success",
  "message": "Prompt updated successfully"
}
```

## Performance Characteristics

```
Component              | Latency      | Notes
─────────────────────────────────────────────────────────────
OKX API Call           | 100-500ms    | Varies by endpoint
Candle Calculation     | 10-50ms      | 10 data points per currency
EMA Calculation        | 5-20ms       | Per currency
MACD Calculation       | 5-20ms       | Per currency  
RSI Calculation        | 5-20ms       | Per currency, per period
ATR Calculation        | 5-20ms       | Per currency
Prompt Building        | 50-200ms     | Format all data
File Write             | 10-100ms     | I/O dependent
Console Print          | 50-200ms     | Depends on output size
Total per cycle        | ~2-3 seconds | For 6 currencies
```

## Scaling Considerations

For future enhancements:

```
Current:
• Single Spring Boot instance
• In-memory calculations
• No database
• No caching
• 3-minute polling

Future Enhancements:
• Add Redis caching layer
  └─ Cache market data for 1 minute
  └─ Reduce API calls by 66%
  
• Add database persistence
  └─ Store historical data
  └─ Enable trend analysis
  
• Add WebSocket support
  └─ Real-time market data
  └─ Eliminate polling delay
  
• Horizontal scaling
  └─ Load balancer for multiple instances
  └─ Shared cache/database
```

---

This architecture provides:
✅ Clear separation of concerns (REST → Business → Data)
✅ Resilient error handling
✅ Scheduled execution
✅ Manual trigger capability
✅ Extensible for future features
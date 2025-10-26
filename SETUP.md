# AI Trader - Prompt Generator Setup Guide

## Overview
This Spring Boot + Kotlin application fetches cryptocurrency market data from the OKX API and generates a formatted prompt.txt file with current market state, trading signals, and account information.

## Features
- ✅ Fetches market data for BTC, ETH, SOL, BNB, XRP, DOGE
- ✅ Calculates technical indicators (EMA, MACD, RSI, ATR)
- ✅ Retrieves account information and active positions
- ✅ Generates formatted prompt.txt output
- ✅ Scheduled execution every 3 minutes
- ✅ REST API for manual triggers
- ✅ Console and file output

## Prerequisites
- Java 21+
- Gradle 7+
- OKX API credentials (API Key, Secret, Passphrase)

## Setup Instructions

### 1. Configure OKX API Credentials
Edit `src/main/resources/application.properties`:

```properties
okx.api.key=YOUR_API_KEY
okx.api.secret=YOUR_API_SECRET
okx.api.passphrase=YOUR_API_PASSPHRASE
```

### 2. Install Dependencies
```powershell
Set-Location "E:\LeetCode\aiTrader"
gradle build
```

### 3. Run the Application
```powershell
Set-Location "E:\LeetCode\aiTrader"
gradle bootRun
```

Or build and run the jar:
```powershell
gradle build
java -jar build/libs/aitrade-0.0.1-SNAPSHOT.jar
```

## Configuration Options

Edit `src/main/resources/application.properties` to customize:

```properties
# OKX API Configuration
okx.api.key=YOUR_API_KEY
okx.api.secret=YOUR_API_SECRET
okx.api.passphrase=YOUR_API_PASSPHRASE
okx.api.base-url=https://www.okx.com

# Trading Configuration
trading.currencies=BTC,ETH,SOL,BNB,XRP,DOGE
trading.time-zone=UTC

# Prompt output configuration
prompt.output-path=./prompt.txt
```

## API Endpoints

### Health Check
```bash
GET http://localhost:8080/api/prompt/health
```

### Get Status
```bash
GET http://localhost:8080/api/prompt/status
```

### Trigger Manual Update
```bash
POST http://localhost:8080/api/prompt/update
```

## Project Structure

```
src/main/kotlin/ru/driics/aitrade/
├── AiTraderApplication.kt          # Main Spring Boot app
├── config/
│   ├── OkxProperties.kt           # Configuration properties
│   └── RestClientConfig.kt        # REST client configuration
├── controller/
│   └── PromptController.kt        # REST endpoints
├── model/
│   └── OkxModels.kt               # Data models
└── service/
    ├── OkxApiService.kt           # OKX API client
    ├── PromptBuilderService.kt    # Prompt generation
    └── PromptSchedulerService.kt  # Scheduled tasks
```

## How It Works

1. **Initialization**: App starts and enables scheduling
2. **Scheduled Task**: Every 3 minutes:
   - Fetches market data for all configured currencies from OKX API
   - Calculates technical indicators (EMA, MACD, RSI, ATR)
   - Retrieves account information and active positions
   - Builds formatted prompt output
   - Writes to `prompt.txt` file
   - Prints to console
3. **Manual Trigger**: Use REST endpoint to trigger updates on-demand

## Technical Indicators Calculated

### Intraday (3-minute intervals):
- **Current Price**: Latest market price
- **EMA (20-period)**: Exponential Moving Average
- **MACD**: Moving Average Convergence Divergence
- **RSI (7-period & 14-period)**: Relative Strength Index

### 4-Hour Timeframe:
- **EMA**: 20-period and 50-period
- **ATR**: 3-period and 14-period Average True Range
- **Volume**: Current and average
- **MACD & RSI**: For longer-term context

## Output Format

The generated `prompt.txt` file includes:
- Session duration and invocation count
- Current timestamp
- Market data for each cryptocurrency
- Account balance and performance metrics
- Open positions with entry/exit plans
- Funding rates and open interest

## Troubleshooting

### API Connection Issues
- Verify OKX API credentials are correct
- Check internet connection
- Ensure API keys have appropriate permissions

### File Write Errors
- Verify write permissions in the prompt output directory
- Check disk space availability

### Build Errors
- Ensure Java 21+ is installed: `java -version`
- Clear gradle cache: `gradle clean build`

## Next Steps

1. **Implement Real OKX API Calls**: The current `OkxApiService` has placeholder implementations. 
   Replace with actual OKX SDK methods once you have the official SDK dependency working.

2. **Add Database Persistence** (Optional): Store historical data for trend analysis

3. **Enhance Signal Analysis**: Add more sophisticated trading signals

4. **Add WebSocket Support**: For real-time data streaming instead of periodic polling

## Support

For issues or questions:
1. Check the logs in console output
2. Verify API credentials and permissions
3. Review OKX API documentation: https://www.okx.com/docs-v5/en/

## Security Notes

⚠️ **Important**: 
- Never commit API credentials to version control
- Use environment variables for sensitive data in production
- Rotate API keys regularly
- Restrict API key permissions to minimum required

## License

MIT License
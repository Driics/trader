# Migration from Spring WebClient to Ktor HTTP Client

## Summary
Successfully migrated the OKX REST client from Spring WebFlux's WebClient to Ktor HTTP Client, reducing dependencies and improving Kotlin-native support.

## Changes Made

### 1. Dependencies (build.gradle.kts)
- Removed: `spring-boot-starter-webflux`
- Removed: `io.projectreactor.netty:reactor-netty-http`
- Removed: `kotlinx-coroutines-reactor`
- Added: Ktor client dependencies (v2.3.7):
  - `ktor-client-core`
  - `ktor-client-cio`
  - `ktor-client-content-negotiation`
  - `ktor-client-logging`
  - `ktor-serialization-kotlinx-json`
- Updated: `kotlinx-coroutines-core` (pure coroutines, no reactor bridge)

### 2. Configuration
- Created: `OkxKtorClientConfig.kt`
  - Configured CIO engine with connection pooling
  - Set timeouts (connect, read, write)
  - Enabled content negotiation with JSON
  - Added logging support
  - Configured default headers

### 3. Service Layer (OkxRestClient.kt)
Updated all HTTP methods to use Ktor:
- `fetchTicker()`, `fetchCandles()`, `fetchFundingRate()`, `fetchOpenInterest()`, `getSwapInstrument()`
- `fetchAccount()`, `fetchOpenPositions()`
- `setLeverage()`, `placeMarketOrderWithAttach()`

### 4. Tests (OkxRestClientIntegrationTest.kt)
- Updated imports from `WebClient` to Ktor `HttpClient`
- Changed client instantiation to use `HttpClient(CIO)`
- Updated constructor parameter from `okxWebClient` to `okxHttpClient`

## Benefits
1. Reduced Dependencies: No longer need Spring WebFlux and Reactor
2. Kotlin-Native: Ktor is built for Kotlin with better DSL support
3. Simplified Code: Cleaner, more idiomatic Kotlin syntax
4. Better Type Safety: Reified generics eliminate verbose type references
5. Maintained Functionality: All timeouts, connection pooling, auth preserved

## Files Modified
- modified: build.gradle.kts
- new: src/main/kotlin/ru/driics/aitrade/config/OkxKtorClientConfig.kt
- modified: src/main/kotlin/ru/driics/aitrade/service/OkxRestClient.kt
- modified: src/test/kotlin/ru/driics/aitrade/service/OkxRestClientIntegrationTest.kt
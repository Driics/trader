# OKX WebClient + Resilience4j Migration

This document describes the migration from `RestTemplate` to `WebClient` with Resilience4j fault tolerance patterns.

## What Changed

### 1. Dependencies
Added:
- `spring-boot-starter-webflux` - Non-blocking HTTP client
- `reactor-netty-http` - Netty-based connection pool
- `resilience4j-spring-boot3` - Fault tolerance (retry, circuit breaker, rate limiter)
- `kotlinx-coroutines-reactor` - Coroutine adapters for Reactor
- `micrometer-core` - Metrics collection
- `wiremock-jre8` - Integration testing

### 2. Configuration
New `OkxHttpProperties` for timeout and connection pool settings:
```yaml
okx:
  http:
    client-type: webclient  # rollout flag: webclient | resttemplate
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
    write-timeout-ms: 10000
    pool:
      max-connections: 200
      pending-acquire-max-count: 2000
      pending-acquire-timeout-ms: 5000
```

Resilience4j policies in `application.yml`:
- **okxMarket**: 50 req/s, 3 retries with exponential backoff, 50% circuit breaker threshold
- **okxAccount**: 20 req/s, 2 retries, 60% circuit breaker threshold  
- **okxTrade**: 10 req/s, 3 retries, 50% circuit breaker threshold

### 3. Code Changes

#### OkxRestClient
- All methods now `suspend` functions
- Use `WebClient` with `.awaitSingleOrNull()` / `.awaitSingle()`
- Annotated with `@Retry`, `@RateLimiter`, `@CircuitBreaker`
- Micrometer timers track latency and status per operation
- MDC context in trading methods for structured logs

#### OkxWebClientConfig
- Custom `ConnectionProvider` with pool settings
- Netty `HttpClient` with timeouts (connect, read, write)
- Reusable `okxWebClient` bean

### 4. Testing
New `OkxRestClientIntegrationTest` with WireMock scenarios:
- 200 OK → parse and return data
- API error codes (51000, 51008) → log and return null
- 5xx → retry with backoff → eventual success/failure
- Timeout → retry → circuit breaker opens
- Metrics verification

## Rollout Strategy

1. **Phase 1 (Current)**: Deploy with `okx.http.client-type=webclient` (default)
2. **Phase 2**: Monitor metrics (`okx.http.*`) and circuit breaker events
3. **Phase 3**: If issues arise, rollback via `client-type=resttemplate`
4. **Phase 4**: After 2 weeks stable, remove RestTemplate fallback code

## Metrics to Monitor

- `okx.http` timer: latency by operation and status
- `resilience4j.circuitbreaker.state`: CLOSED | OPEN | HALF_OPEN
- `resilience4j.retry.calls`: success vs failure
- `resilience4j.ratelimiter.available.permissions`: throttling health

## Benefits

✅ Non-blocking I/O → better throughput under load  
✅ Connection pooling → reduced latency  
✅ Automatic retries with jitter → resilient to transient errors  
✅ Circuit breaker → fast-fail during outages  
✅ Rate limiting → respect OKX API limits  
✅ Detailed metrics → observability  

## Troubleshooting

**Circuit breaker opens frequently:**
- Check `resilience4j.circuitbreaker.okxMarket.failure-rate-threshold` (default 50%)
- Increase `wait-duration-in-open-state` if OKX is slow to recover

**Rate limiter rejects requests:**
- Verify `limit-for-period` matches your OKX tier limits
- Consider reducing `trading.max-concurrent-symbols`

**Timeouts under load:**
- Increase `read-timeout-ms` or `pool.max-connections`
- Check network latency to OKX

## References

- [Resilience4j Spring Boot 3 Docs](https://resilience4j.readme.io/docs/getting-started-3)
- [Spring WebClient Docs](https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html)
- [Reactor Netty Connection Pool](https://projectreactor.io/docs/netty/release/reference/index.html#_connection_pool)
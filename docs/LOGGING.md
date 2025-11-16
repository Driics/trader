# Structured Logging Guide

This document describes the structured logging implementation for the AI Trading System.

## Overview

The application uses structured JSON logging for better integration with log aggregation systems like ELK Stack (Elasticsearch, Logstash, Kibana) and Grafana Loki.

## Features

- **JSON Output**: All logs in production are output as JSON for easy parsing
- **Correlation IDs**: Automatic request tracking across services
- **Business Events**: Structured logging for important business events
- **Environment-based Configuration**: Different log levels and formats per environment

## Log Formats

### Development/Local
- Human-readable console output with correlation IDs
- JSON file output for testing

### Production
- JSON console output (for container logs)
- JSON file output with rotation
- Optimized for ELK/Loki ingestion

## Correlation IDs

Correlation IDs are automatically generated for each HTTP request and included in all log entries. They can be:

- Extracted from HTTP headers: `X-Correlation-ID`, `X-Request-ID`, `X-Trace-ID`
- Auto-generated if not present
- Propagated in response headers

### Usage in Code

```kotlin
import ru.driics.aitrade.common.logging.CorrelationId

// Generate and set correlation ID
CorrelationId.generate()

// Use in a block (auto-cleanup)
CorrelationId.withCorrelationId("my-id") {
    // Your code here
}
```

## Business Event Logging

Important business events are logged using `BusinessEventLogger`:

```kotlin
import ru.driics.aitrade.common.logging.BusinessEventLogger

// Log order placement
BusinessEventLogger.orderPlaced(
    symbol = "BTC",
    orderId = "12345",
    clOrdId = "CL-123",
    side = "buy",
    contracts = BigDecimal("10"),
    price = BigDecimal("50000"),
    tp = BigDecimal("51000"),
    sl = BigDecimal("49000"),
    leverage = 10,
    costUsd = BigDecimal("5000")
)

// Log order rejection
BusinessEventLogger.orderRejected(
    symbol = "BTC",
    clOrdId = "CL-123",
    reason = "Insufficient margin",
    errorCode = "51000"
)

// Log update cycle
BusinessEventLogger.updateCycle(
    cycleNumber = 42,
    durationMs = 1500,
    symbolsProcessed = 6,
    positionsPlaced = 2,
    success = true
)
```

## Structured Logger

For custom structured logging:

```kotlin
import ru.driics.aitrade.common.logging.structuredLogger

val log = structuredLogger<MyClass>()

log.info(
    event = "custom_event",
    "field1" to "value1",
    "field2" to 42,
    "field3" to true
)
```

## Log Levels by Environment

### Development (`dev`, `local`)
- Root: `DEBUG`
- Application: `DEBUG`
- Spring: `INFO`
- Ktor: `DEBUG`

### Production (`prod`, `production`)
- Root: `INFO`
- Application: `INFO`
- Business Events: `INFO`
- Spring: `WARN`
- Ktor: `WARN`

### Test
- Root: `WARN`
- Minimal logging for tests

## ELK Stack Integration

### Logstash Configuration

```ruby
input {
  file {
    path => "/path/to/logs/application.json"
    codec => json
    start_position => "beginning"
  }
}

filter {
  json {
    source => "message"
  }
  
  # Extract correlation ID
  if [correlationId] {
    mutate {
      add_field => { "[@metadata][correlationId]" => "%{correlationId}" }
    }
  }
}

output {
  elasticsearch {
    hosts => ["localhost:9200"]
    index => "trading-system-%{+YYYY.MM.dd}"
  }
}
```

### Elasticsearch Index Template

```json
{
  "index_patterns": ["trading-system-*"],
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "level": { "type": "keyword" },
      "event": { "type": "keyword" },
      "correlationId": { "type": "keyword" },
      "symbol": { "type": "keyword" },
      "orderId": { "type": "keyword" },
      "message": { "type": "text" }
    }
  }
}
```

## Grafana Loki Integration

### Promtail Configuration

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: trading-system
    static_configs:
      - targets:
          - localhost
        labels:
          job: trading-system
          __path__: /path/to/logs/application.json
    pipeline_stages:
      - json:
          expressions:
            timestamp: "@timestamp"
            level: level
            event: event
            correlationId: correlationId
      - labels:
          level:
          event:
          correlationId:
      - output:
          source: message
```

## Kibana Queries

### Find all order placements
```
event: "order_placed"
```

### Find orders by symbol
```
event: "order_placed" AND symbol: "BTC"
```

### Find errors with correlation ID
```
level: "ERROR" AND correlationId: "abc-123"
```

### Track request flow
```
correlationId: "abc-123"
```

## Grafana Queries (Loki)

### Order placement rate
```logql
rate({job="trading-system"} |= "order_placed" [5m])
```

### Error rate by event
```logql
sum(rate({job="trading-system"} | json | level="ERROR" [5m])) by (event)
```

### Average order cost
```logql
avg_over_time({job="trading-system"} | json | event="order_placed" | unwrap costUsd [1h])
```

## Configuration

### application.yml

```yaml
logging:
  level:
    root: INFO
    ru.driics.aitrade: INFO
    ru.driics.aitrade.common.logging.BusinessEventLogger: INFO
  file:
    name: ./logs/application.log
    max-size: 100MB
    max-history: 30
```

### Environment Variables

- `LOG_DIR`: Directory for log files (default: `./logs`)
- `LOG_FILE`: Log file name (default: `application`)
- `SPRING_PROFILES_ACTIVE`: Environment profile (`dev`, `prod`, `test`)

## Best Practices

1. **Use BusinessEventLogger for important events**: Order placements, rejections, position changes
2. **Include correlation IDs**: All logs automatically include correlation IDs
3. **Use structured fields**: Prefer structured logging over string concatenation
4. **Log levels**: Use appropriate levels (DEBUG for development, INFO for production)
5. **Avoid sensitive data**: Never log API keys, passwords, or personal information

## Example Log Output

### JSON Format (Production)
```json
{
  "@timestamp": "2024-01-15T10:30:45.123Z",
  "level": "INFO",
  "service": "trading-system",
  "environment": "prod",
  "event": "order_placed",
  "correlationId": "abc-123-def-456",
  "symbol": "BTC",
  "orderId": "12345",
  "clOrdId": "CL-123",
  "side": "buy",
  "contracts": "10",
  "price": "50000",
  "takeProfit": "51000",
  "stopLoss": "49000",
  "leverage": 10,
  "costUsd": "5000",
  "message": "[order_placed] symbol=\"BTC\", orderId=\"12345\", ..."
}
```

### Human-Readable Format (Development)
```
2024-01-15 10:30:45.123 [main] INFO  [abc-123-def-456] BusinessEventLogger - [order_placed] symbol="BTC", orderId="12345", ...
```


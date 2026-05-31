# Running a backtest

The backtest validates a `Strategy` over historical candles **offline** — no live funds, no WebSocket,
no dependence on the unverified B0 realized-PnL read. Two steps: get candles, run the harness.

## 1. Get candles (network — you run this)

The parser consumes OKX's native candle arrays directly, so fetching is just `curl` + `jq`. Each JSONL
line is one OKX candle array `[ts, open, high, low, close, vol, ...]`; the parser sorts ascending, so the
newest-first order OKX returns is fine.

Write the file under `data/` — that directory is gitignored, so captured market data can never be
committed by accident (a bare `btc.jsonl` in the repo root would NOT be ignored).

```bash
# 300 most-recent 1-hour candles for BTC-USDT-SWAP
curl -s 'https://www.okx.com/api/v5/market/candles?instId=BTC-USDT-SWAP&bar=1H&limit=300' \
  | jq -c '.data[]' > data/btc.jsonl
```

For a longer history, page backwards with `history-candles` (max 100/call) using `after=<oldest-ts-you-have>`
and append each page to the file; order does not matter (the parser sorts):

```bash
curl -s 'https://www.okx.com/api/v5/market/history-candles?instId=BTC-USDT-SWAP&bar=1H&limit=100&after=1700000000000' \
  | jq -c '.data[]' >> data/btc.jsonl
```

`bar` can be `1m`, `5m`, `15m`, `1H`, `4H`, `1D`, … — it sets the timeframe the strategy's RSI/EMA/MACD
are computed on. **Do not commit captured market data** (the report is written next to the input, so it
also lands under the gitignored `data/`).

## 2. Run the harness (offline)

The harness (`BacktestHarnessTest`) runs only when `BACKTEST_FILE` is set, so a normal `gradlew test` skips
it. It prints the report and writes it next to the input as `<file>.report.txt`.

```powershell
# PowerShell
$env:BACKTEST_FILE = "data/btc.jsonl"
# optional: $env:BACKTEST_SYMBOL = "BTC-USDT-SWAP";  $env:BACKTEST_EQUITY = "10000"
.\gradlew.bat test --tests '*BacktestHarnessTest*'
```

```bash
# bash
BACKTEST_FILE=data/btc.jsonl ./gradlew test --tests '*BacktestHarnessTest*'
```

Defaults (`BacktestRunner.defaultConfig`): BTC-USDT-SWAP contract specs (ctVal 0.01 BTC, lot/min 0.1),
5 bps taker fee, 5% margin buffer, 1% equity risk/trade, 30-bar warmup, $10,000 starting equity. For a
different instrument, the contract specs MUST be changed to match it (see `InstrumentSpec`) — the defaults
are BTC-USDT-SWAP only.

## What the number does and does NOT include

Honest by construction (see `docs/backtest-engine-design.md`): no look-ahead, fills at the *next* bar's
open, pessimistic stop-first exits, every entry requires a stop, sizing through the production
`OrderSizingPolicy`. The equity curve is **gross of funding and slippage** — both are listed under
"NOT modelled" in the report. Treat a profitable curve as necessary, not sufficient, evidence.

## Programmatic use

`BacktestRunner.run(jsonl, symbol, strategy, config)` is pure (`String -> BacktestResult`) — drop in any
`Strategy` implementation behind the seam to compare strategies on the same candles.

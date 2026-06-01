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

If the run reports `SKIPPED` instead of producing a report, a warm Gradle daemon likely didn't pick up
the freshly-set env var. Re-run with `--no-daemon` (or set `BACKTEST_FILE` persistently in your shell):

```powershell
$env:BACKTEST_FILE = "data/btc.jsonl"; .\gradlew.bat --no-daemon test --tests '*BacktestHarnessTest*'
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

## Comparing strategies (parameter sweep)

`StrategySweepHarnessTest` runs an RSI + Donchian grid over one candle file and writes a best-first
comparison table to `<file>.sweep.txt`:

```powershell
$env:BACKTEST_FILE = "data/btc.jsonl"
.\gradlew.bat --no-build-cache --rerun-tasks test --tests '*StrategySweepHarnessTest*'
```

Empirical finding (6-month BTC-USDT-SWAP 1H): mean-reversion (RSI) loses across every parameterization;
trend-following (Donchian breakout) is profitable (best `donchian 10` +23.6%, PF 1.42). **A grid winner on
one window is the textbook overfit** — channel-period sensitivity here is high, so validate out-of-sample
before trusting it.

## Validate out-of-sample (walk-forward)

A sweep winner is selected *and* judged on the same data — the textbook overfit. `WalkForwardHarnessTest`
re-selects the grid winner on a train slice and measures it on **unseen** test data:

```powershell
$env:BACKTEST_FILE = "data/btc.jsonl"; $env:WF_FOLDS = "3"
.\gradlew.bat --no-build-cache --rerun-tasks test --tests '*WalkForwardHarnessTest*'
```

Writes `<file>.walkforward.txt`: an in-sample/out-of-sample split (verdict: *held up* vs *collapsed*) plus
anchored folds. **Read two things:** is the out-of-sample return positive, and is the winner *stable*
across folds (a winner that jumps from fold to fold is an overfit signal, even if each fold is positive).
SLOW — it runs the full grid on each train window (minutes on a 6-month file). It is a validation step,
not a hot path.

## Backtesting the AI (record → replay)

The live AI flow is async, paid, and non-deterministic, so it can't be called inside the deterministic
backtest loop. Instead: **record once** (network, paid), then **replay many** (offline, free).

1. **Record** (calls the live LLM — costs money; loads the Spring context):

   ```powershell
   $env:AI_RECORD_FILE = "data/btc.jsonl"     # candles to record over
   # optional: $env:AI_RECORD_CADENCE="24"  (bars between LLM calls)  $env:AI_RECORD_MAX="100"  (cost cap)
   .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*AiRecorderHarnessTest*'
   ```

   Writes `data/btc-ai.jsonl` (one decision per line). If the AI needs the templated prompt rather than the
   pure `PromptBuilder` one, swap `PromptTemplateService` into `AiRecorderHarnessTest` (mirror
   `BuildPromptUseCase.buildTemplatePrompt`).

2. **Replay** (offline, deterministic, free — sweep `AI_MIN_CONFIDENCE` for free):

   ```powershell
   $env:BACKTEST_FILE = "data/btc.jsonl"; $env:AI_DECISIONS_FILE = "data/btc-ai.jsonl"
   # optional: $env:AI_MIN_CONFIDENCE="0.6"
   .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*AiReplayHarnessTest*'
   ```

   The report prints the **decision↔candle match-rate** — if it's near zero, the decision log and candles
   don't line up (wrong instrument/timeframe), which would otherwise look like "the AI never traded".

**AI replay caveat (in the report):** flat-state recording validates the AI's *entry signals under fixed
SL/TP brackets only* — not its hold/close/invalidation logic or drawdown-aware sizing. A profitable AI
replay is necessary, not sufficient.

## Programmatic use

`BacktestRunner.run(jsonl, symbol, strategy, config)` is pure (`String -> BacktestResult`) — drop in any
`Strategy` (incl. `DonchianBreakoutStrategy`, `RecordedAiStrategy`) behind the seam. `BacktestRunner.replayAi(...)`
and `ParameterSweep.run(...)` are likewise pure.

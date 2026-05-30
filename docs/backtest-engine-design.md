# Backtest Engine + Strategy Seam — Design

## Why
Everything built so far hardens the *plumbing* (risk gates, kill-switch, idempotency, streaming, order
semantics). The **strategy has never been validated** — the system trades but no one knows whether the
decisions make money. This adds the missing layer: a `Strategy` seam that breaks the hardcoded AI path,
and a deterministic simulation engine that answers *does this strategy make money* offline, with no real
funds, no live WS, and no dependence on the still-unverified B0 PnL read.

Chosen because it is the rare *major/architectural* build that is also **fully verifiable in the
blind/batched/no-network loop**: fill simulation, PnL, drawdown, win-rate are pure arithmetic over
candle data, reusing the already-tested `IndicatorCalculator` and `OrderSizingPolicy`.

## Architecture
- **`Strategy` port** (`domain/strategy/`): `fun decide(state: MarketState): List<StrategyDecision>` —
  sync, **pure**, deterministic. Reuses the live `MarketState` as the context, so a strategy validated
  in backtest transfers to live unchanged (that is the architectural payoff). The live async AI flow is
  adapted behind this port *later*; v1 ships a deterministic rule strategy.
- **Backtest engine** (`domain/backtest/`, pure): iterates bars, builds a `MarketState` per step from
  `bars[0..i]` (reusing `IndicatorCalculator`), runs the strategy, simulates fills/exits, marks equity.

## The five invariants that keep the number honest
1. **No look-ahead.** At step `i` the strategy sees only `bars[0..i]`. The `subList(0, i+1)` slice is the
   SINGLE chokepoint, pinned by a probe-strategy test that records the max timestamp ever observed and
   asserts it never exceeds the decision bar. (Test, not comment.)
2. **Fill at `open[i+1]`, never `close[i]`.** Acting at the close you just used to decide assumes
   zero latency — the classic backtest lie. Decisions queue a pending order filled at next bar open.
   Loop ordering (the #1 engine bug):
   ```
   step i:
     1. fill pending orders (decided at i-1) at open[i]
     2. exit pass: open positions vs bar[i] [low,high]  (incl. one just opened this bar)
     3. mark equity at close[i]
     4. build state from bars[0..i]; strategy.decide(); queue orders for open[i+1]
   ```
3. **Pessimistic SL-first.** If both stop and target are touchable in one bar, the STOP fills (OHLC
   can't reveal intrabar order, so assume the worse outcome).
4. **Leverage requires a stop.** A leveraged position with no stop could "recover" from a drawdown that
   would have been force-closed in reality → fabricated equity. v1 rule: every entry must carry a stop;
   no-stop (leveraged) entries are **rejected with a logged count**. Funding cost is **omitted but
   logged** as a known omission (so the curve is never read as net-of-funding).
5. **Sizing reuses `OrderSizingPolicy`.** Per-symbol instrument specs (`ctVal/ctValCcy/lotSz/minSz`)
   live in `BacktestConfig`. Forking a "simpler" sizing model would validate a system that sizes
   differently than production — defeating the whole point.

## Context coupling note
The sim synthesizes `AccountInfo` + `positions` each bar. These `Position`/`AccountInfo` fields are
**live-only and undefined in backtest** — a strategy must not depend on them: `slOid`, `tpOid`,
`entryOid`, `liquidationPrice`, `notionalUsd`, `sharpeRatio`.

## Metrics (v1)
`totalReturn`, `maxDrawdown`, `winRate`, `profitFactor`, `#trades`, gross profit/loss, net PnL.
**Sharpe deferred** (annualization assumptions invite a misleading number).

## Build plan (batched, each pure unit + boundary tests)
- **Batch 1 — seam + mechanics** ✅: `Strategy`/`StrategyDecision`; `Bar`; sim models; `resolveExit`
  (pessimistic SL-first); `realizedPnlUsd`. Tests.
- **Batch 2 — metrics** ✅: `PerformanceMetrics.from(...)`, `maxDrawdownPct`. Tests.
- **Batch 3 — baseline strategy** ✅: `RsiReversionStrategy` (deterministic). Tests.
- **Batch 4 — engine** (next): `BacktestEngine` loop (ordering above), `MarketStateBuilder` from a bar
  window (reuse `IndicatorCalculator`), the no-look-ahead probe test, `OrderSizingPolicy` integration.
- **Batch 5 — I/O**: JSONL `CandleSource` + `@Profile("backtest")` runner + report; (separate, network,
  user-run) OKX history fetch runner.
- **Deferred**: AI-flow-as-strategy adapter; funding model; Sharpe.

> v1 (batches 1–3) is the **tested core, not a runnable backtest** — no equity curve until the engine
> (4) and candle source/runner (5) land.

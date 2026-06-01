# Design — Trade-Close Tracking + Journal Analytics Read API

> Date: 2026-06-01 · Status: approved (brainstorm) → ready for implementation plan · Branch: `feature/mr-29`

## 1. Context & Goal

The trade journal (`TradeJournalPort`) currently records **entries** (`trade_order`), **fills**
(`fill`), and **per-cycle account snapshots** (`pnl_snapshot`) — but never per-trade **closes**. TP/SL
are attached server-side at entry, so when a position closes on OKX the bot never observes the exit,
and **true per-trade performance (win-rate, avg-R, profit-factor) is not computable** from today's data.

**Goal.** A read-only JSON analytics API over the journal that reports realized trading performance,
powered by a new per-cycle **trade-close capture** mechanism. This is the "lens" for watching the R1
paper soak; the captured realized PnL per closed position also feeds the **B0 PnL-reconciliation** gate.

**Primary consumer:** an operator (and the future web UI) watching a multi-day paper soak.

## 2. Decisions (brainstorm outcomes)

| # | Decision | Choice |
|---|----------|--------|
| Scope | Ship analytics on existing data, or add trade-close tracking? | **Add trade-close tracking** (unlock true win-rate/avg-R/profit-factor) |
| Capture | How to detect closes + realized PnL? | **Poll `/account/positions-history` once per cycle** (not real-time WS) |
| Surface | How is it viewed? | **JSON API only** (no built-in HTML, no Grafana) |
| R denominator | How is the R-multiple defined? | **`R = realizedPnl / risk_usd`** (journal `risk_usd`, currently `null`) |
| Metric location | Where does the math live? | **Pure domain service** (`PerformanceAnalytics`), not SQL aggregation |

## 3. Architecture (hexagonal — mirrors existing patterns)

```
positions-history (OKX) ─▶ TradeCloseCollector ─▶ TradeJournalPort.recordClose ─▶ trade_close table
                            (per cycle, fail-safe)

trade_order / trade_close / pnl_snapshot ─▶ TradeJournalQueryPort (read)
                                              └▶ PerformanceAnalytics (pure) ─▶ AnalyticsController ─▶ /api/analytics/*
```

- **Write side**: extend `TradeJournalPort` with `recordClose(JournaledClose)`, mirroring
  `recordOrder/recordFill/recordPnlSnapshot`. `JdbcTradeJournal` implements the INSERT; `NoOpTradeJournal`
  no-ops it.
- **Read side**: a *new* `TradeJournalQueryPort` (kept separate from the write port) returning **raw rows**
  (`closedTrades(filter)`, `entries(filter)`, `pnlSnapshots(filter)`). JDBC adapter `JdbcTradeJournalQuery`;
  a `NoOpTradeJournalQuery` returns empty when the journal is disabled.
- **Metrics**: `PerformanceAnalytics` — a pure domain service in a new `domain/analytics` package (no DB,
  no Spring), in the same spirit as `OrderSizingPolicy` / `IndicatorCalculator`. Raw rows in, metric
  models out. Its result models (`PerformanceSummary`, `SymbolPerformance`, `EquityPoint`) live in
  `domain/analytics` too.

## 4. Data capture

### 4.1 `trade_close` table (new Liquibase changeset, appended to `db.changelog-master.yaml`)

| column | type | notes |
|--------|------|-------|
| `id` | BIGINT autoincrement PK | |
| `recorded_at` | TIMESTAMP | when journaled |
| `pos_id` | VARCHAR(64) | OKX position id — **UNIQUE** (idempotent inserts) |
| `inst_id` | VARCHAR(64) | e.g. `BTC-USDT-SWAP` |
| `symbol` | VARCHAR(64) | derived (`inst_id.substringBefore("-")`) |
| `side` | VARCHAR(64) | position direction (OKX `direction`: long/short) |
| `realized_pnl` | NUMERIC(38,18) | USD |
| `open_time` | TIMESTAMP | |
| `close_time` | TIMESTAMP | |
| `mode` | VARCHAR(64) | `TradingMode` at capture |
| `demo` | BOOLEAN | |

> `open_avg_px`/`close_avg_px` were intentionally **not** persisted — no metric consumes them, and avg-R
> uses the entry's intended `risk_usd`, not the realized stop distance. Add them later only if a metric needs them.

### 4.2 `TradeCloseCollector` (per-cycle, fail-safe)

- Runs **once per cycle**, invoked from `UpdateCycleOrchestrator` (near the existing `recordPnlSnapshot`
  call in `stageExecute`), only when the journal is enabled.
- Each cycle: fetch closed positions via `OkxAccountClient.fetchPositionsHistory` (behind a
  `ClosedPositionsPort`) returning a domain list of `posId`, `instId`, `side`, `realizedPnl`, `openTime`,
  `closeTime`, bounded to closes after the in-memory **high-water mark**.
- **Dedup + gap-proofing:** the high-water mark (last processed `close_time`) is **rehydrated on boot**
  from `SELECT max(close_time) FROM trade_close`. `recordClose` reports `JOURNALED` / `DUPLICATE` /
  `FAILED`, and the collector advances the mark **only over the confirmed contiguous (oldest-first)
  prefix** — stopping at the first hard `FAILED` write, so a failed close is retried next cycle and never
  skipped (even across a restart reseed from `MAX(close_time)`). The UNIQUE `pos_id` constraint makes
  re-journaling idempotent.
- **Fail-safe:** any fetch/parse/insert error is logged at WARN and swallowed — a journal/exchange hiccup
  never breaks a trading cycle (same contract as the rest of the journal).

### 4.3 `risk_usd` fix

`ExecuteAiDecisionsUseCase.handleSuccessfulOrder` constructs `JournaledOrder(..., riskUsd = null, ...)`.
Change it to journal `args.riskUsd` (the AI's intended dollar risk, available at entry) so R is
computable. One-line change; covered by an updated `JdbcTradeJournalTest`/use-case test assertion.

### 4.4 Mode boundary (important)

Close capture reads **real OKX positions**, so it is populated in **PAPER** (`okx.paper=true`,
`demo-mode=false` → orders hit OKX's demo env, real positions) and **LIVE**. In pure local **SIMULATION**
(`demo-mode=true`, fills simulated locally, nothing sent to OKX) there are no OKX positions to poll, so
no closes are captured and analytics are empty. **The R1 soak must run in PAPER mode** for the journal +
analytics to have data. Documented as a known boundary; simulating closes locally is out of scope.

## 5. Metrics — `PerformanceAnalytics` (pure)

Inputs: raw closed-trade rows, raw entry rows (for `risk_usd` linkage), raw `pnl_snapshot` series.
All amounts USD; `realized_pnl` is the per-close figure from OKX.

| Metric | Definition |
|--------|------------|
| Total trades | count of closes |
| Win rate | `wins / total`, where win = `realizedPnl > 0`; `realizedPnl == 0` is a **scratch** (excluded from wins, reported separately) |
| Gross profit / loss | `Σ realizedPnl(wins)` / `|Σ realizedPnl(losses)|` |
| Profit factor | `grossProfit / grossLoss`; **null** if no losses |
| Net realized PnL | `Σ realizedPnl` |
| Avg win / avg loss | `grossProfit / #wins` / `grossLoss / #losses` |
| Expectancy ($/trade) | `netPnl / total` |
| Avg-R | mean of per-trade `realizedPnl / risk_usd`; see linkage below. Trades with missing/zero `risk_usd` are excluded from R stats and counted as `rUnavailable` |
| Max drawdown | max peak-to-trough decline of the `pnl_snapshot.account_value` series, reported in **USD and %** |
| Equity curve | time series `(timestamp, accountValue, drawdownPct)` from `pnl_snapshot` |
| Per-symbol | all of the above grouped by `symbol` |

**Close→entry linkage for R:** match a close to the most recent `trade_order` with `status='PLACED'`
for the same `inst_id` whose `recorded_at <= close.close_time` (an entry always precedes its own close),
and read that entry's `risk_usd`. **Assumption:** ~one open position per symbol at a time (current
behavior — no pyramiding/scaling). This limitation is documented; if scaling is ever added, linkage needs
revisiting.

**Filters (all metrics):** `from`, `to` (time range), `mode`, `symbol`. Default = all-time, all modes
(the soak runs in PAPER, so PAPER/demo trades must be included by default — do **not** filter out demo).

## 6. API — read-only, `/api/analytics`

`AnalyticsController` (Spring MVC, sibling to `TradingSystemController`/`RiskController`):

| Endpoint | Returns |
|----------|---------|
| `GET /api/analytics/summary` | `PerformanceSummary` (headline metrics §5) |
| `GET /api/analytics/by-symbol` | `List<SymbolPerformance>` |
| `GET /api/analytics/equity-curve` | `List<EquityPoint>` `(ts, accountValue, drawdownPct)` |
| `GET /api/analytics/trades` | recent closed trades w/ computed R (paginated: `limit`, `offset`) |

All accept the optional filters from §5 as query params.

**Journal-off behavior:** when `trade-journal.enabled=false`, the wired `NoOpTradeJournalQuery` returns
empty, and every response carries a top-level `"enabled": false` with empty/zero metrics — HTTP `200`,
not an error — so a future UI can render "journal disabled."

Result models live in `domain/analytics` (alongside `PerformanceAnalytics`) as pure data classes;
serialized as JSON by the existing Jackson config.

## 7. Error handling

- **Capture:** fail-safe (never breaks a cycle); idempotent inserts via UNIQUE `pos_id`.
- **Query/API:** DB read errors surface as a clean `500` via the existing `GlobalExceptionHandler` — no
  internal/stack leakage. Bad filter params → `400` through the existing validation path.

## 8. Testing

- **`PerformanceAnalytics`** (the bulk) — pure unit tests: every metric + edge cases (no trades, all
  wins, all losses, missing `risk_usd`, scratch trades, single trade, drawdown shapes, profit-factor with
  zero losses).
- **`TradeCloseCollector`** — mocked positions-history source: new-close detection, high-water-mark
  dedup **across a simulated restart**, idempotent re-poll, fail-safe on fetch/insert error.
- **`JdbcTradeJournalQuery` + `recordClose`** — H2 integration (project already H2-tests the journal in
  `JdbcTradeJournalTest`), incl. the UNIQUE-`pos_id` idempotency.
- **`AnalyticsController`** — MockMvc: each endpoint, filter passthrough, and the journal-off `enabled:false`
  envelope.
- **Liquibase** — new changeset applies cleanly on H2 (tests) and Postgres (portable types, as the
  existing changelog).
- Full `gradlew test` green is the gate.

## 9. Scope boundaries (YAGNI)

- **JSON only** — no built-in HTML dashboard, no Grafana (deferred; the future web UI consumes these
  endpoints).
- **Polling only** — no real-time private-WS close capture.
- **No pyramiding** — one-position-per-symbol assumption for R linkage.
- **No new auth** — read-only endpoints follow the existing controllers' exposure posture. Flagged as a
  **deploy consideration** (these expose financial data — don't expose publicly), not built in v1.
- **PAPER/LIVE only** — pure local SIMULATION produces no closes (§4.4).

## 10. Affected / new files

**New:** `domain/journal/JournaledClose.kt`, `domain/analytics/PerformanceAnalytics.kt` + result models
(`PerformanceSummary`, `SymbolPerformance`, `EquityPoint`), `domain/ports/TradeJournalQueryPort.kt`,
`application/journal/TradeCloseCollector.kt`, `infra/persistence/JdbcTradeJournalQuery.kt` +
`NoOpTradeJournalQuery.kt`, `controller/AnalyticsController.kt`, a new changeSet in
`resources/db/changelog/db.changelog-master.yaml`, and matching tests.

**Changed:** `domain/ports/TradeJournalPort.kt` (+`recordClose`), `infra/persistence/JdbcTradeJournal.kt`
& `NoOpTradeJournal.kt` (impl `recordClose`), `application/usecase/ExecuteAiDecisionsUseCase.kt` (journal
`risk_usd`), `application/orchestrator/UpdateCycleOrchestrator.kt` (invoke the collector),
`config/ApplicationWiring.kt` / `TradeJournalPersistenceConfig` (wire collector + query port + NoOp),
`service/okx/OkxAccountClient.kt` (production positions-history fetch method).

## 11. Future / follow-ups (out of scope here)

- Upgrade R to denominator **(B)** (`realizedPnl / (|entry−SL| × contracts × ctVal)`) if `risk_usd`
  proves unreliable during the soak.
- HTML dashboard / Grafana-as-code over these endpoints (roadmap X4-adjacent).
- Rejection-reason analytics from `trade_order` (status=REJECTED) — cheap add, deferred to keep v1 focused.
- Pyramiding-aware close→entry linkage if scaling is introduced.

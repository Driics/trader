# R1 — OKX-demo Paper Soak Runbook

> The single highest-leverage step toward 1.0. A multi-day run in **PAPER** mode (real orders to OKX's
> **DEMO** environment — no real funds) that does three things at once:
>
> 1. **Proves the autonomous loop survives real conditions** — WS reconnects, the scheduler under load, AI
>    cost/latency drift, the trade journal under write volume. No unit test covers this.
> 2. **Validates behaviour shipped 2026-06-01 that has never run live** — the bot now **shorts** (the
>    short-enabling prompt fix) and every position is bounded by the **per-trade risk cap**. Watch both.
> 3. **Generates the real `/account/bills`** the daily-loss-cap PnL reconciliation (B0) needs to be trusted
>    with live funds — see `docs/pnl-reconciliation.md`.

---

## Prerequisites

- **OKX DEMO API key** (api-key + secret + passphrase) created in the OKX *demo/simulated* trading area,
  **plus a `broker-id`** — PAPER mode refuses to start without it (it's required for the `wspap` demo WS).
- **OpenRouter key + a model that actually answers** (a free model that 429s will stall the loop — use a
  cheap paid model, or a free model you've confirmed responds). The default is `qwen/qwen3-max`.
- *(Recommended)* a Postgres/Supabase datasource so the **trade journal** is ON — it persists orders/fills/PnL
  and is what exercises "journal under load" and powers the analytics API + close-capture. The soak runs
  without it, but then you lose that payoff.

---

## Mode = PAPER

The effective mode is derived from two flags (`TradingMode.resolve`); a `TradingModeGuard` enforces them at
startup:

| `TRADING_DEMO_MODE` | `OKX_PAPER` | Mode | Orders go to |
|---|---|---|---|
| `true` (default) | any | **SIMULATION** | nowhere (simulated locally) |
| `false` | `true` | **PAPER** ← _this soak_ | OKX **DEMO** (x-simulated-trading) — no real funds |
| `false` | `false` | **LIVE** | REAL funds (refused unless `TRADING_CONFIRM_LIVE=true`) |

So PAPER = `TRADING_DEMO_MODE=false` **and** `OKX_PAPER=true` **and** a `broker-id`. (Close-capture/analytics
only populate in PAPER/LIVE — another reason to soak in PAPER, not SIMULATION.)

---

## Launch (PowerShell)

```powershell
# --- OKX DEMO creds (simulated-trading) ---
$env:OKX_API_KEY        = "<demo-key>"
$env:OKX_API_SECRET     = "<demo-secret>"
$env:OKX_API_PASSPHRASE = "<demo-passphrase>"
$env:OKX_BROKER_ID      = "<broker-id>"   # REQUIRED for PAPER
$env:OKX_PAPER          = "true"
$env:TRADING_DEMO_MODE  = "false"         # false + paper=true => PAPER (not SIMULATION)

# --- actually place orders (the whole point of a soak) ---
$env:TRADING_AUTO_EXECUTE = "true"

# --- AI ---
$env:OPENROUTER_API_KEYS = "<key>"
$env:TRADING_AI_MODEL    = "qwen/qwen3-max"   # a model that responds (not a 429-prone free one)

# --- (recommended) trade journal ON ---
$env:TRADE_JOURNAL_ENABLED = "true"
$env:SPRING_DATASOURCE_URL      = "jdbc:postgresql://<host>:5432/<db>"
$env:SPRING_DATASOURCE_USERNAME = "<user>"
$env:SPRING_DATASOURCE_PASSWORD = "<pass>"

.\gradlew.bat bootRun --no-daemon
```

Startup must log the banner **`TRADING MODE: PAPER — real orders are sent to OKX's DEMO environment (no real
funds)`**. If you see `SIMULATION`, `demo-mode` is still true. If it refuses to start, you're missing the
broker-id (PAPER) or tripped the LIVE guard (you set both flags off).

**Tunables worth setting for a soak** (in `application.yml` / env):
- `ai.trade.scheduler.interval-ms` — default 900000 (15 min). Leave it for a realistic soak, or shorten to
  exercise more cycles per hour.
- `trading.okx-timeouts.currency-fetch` — default 15s; raise if your link to OKX is slow (the new guard will
  otherwise skip data-starved cycles with a `market.data.empty` metric).
- `trading.risk.max-risk-per-trade-pct` (default 0.02) and `trading.risk.max-daily-loss-usd` (default 50).

---

## What to watch

### 1. Loop survival
- Console: `═══ Update Cycle #N ═══`, `Signals processed: A accepted, R rejected`, `Execution complete: P
  placed, S skipped`, and clean WS lines (`Connected to wss://wspap...`, `Restoring N subscriptions`,
  reconnects that recover).
- Metrics (`/actuator/prometheus`): `ai.analyze` (success/latency), `risk.gate.blocked`,
  `guard.rejected`, and the new **`market.data.empty`** (should be ~0 once the pool is warm).
- Red flags: repeated `AI schema rejected`, runaway 429s, a WS that reconnect-loops forever, climbing cycle
  duration.

### 2. New behaviour shipped 2026-06-01 (the reason this soak matters now)
- **Shorts actually happen.** Confirm you see `sell`/SHORT orders, not just buys (the old long-only bug).
  Check `/api/trading/status`, the journal, and the OKX demo positions.
- **The risk cap binds.** No single position should risk more than ~`max-risk-per-trade-pct` of equity. Spot-
  check a placed order's size against `risk_usd` and the stop distance.
- **Empty-data guard / prompt filter.** On a flaky data cycle you should see a clean skip (`market.data.empty`
  warn), not an empty-prompt `{}` schema rejection.

### 3. PnL data for B0 (the real goal)
- `getTodaysRealizedPnlUsd` auto-logs its composition: **DEBUG** `Daily realized PnL=… by bill type=… ccys=…`
  and a **WARN** on any non-USD-settled contributor. Watch for that WARN — it's the unambiguous "summing
  mixed currencies" signal. (Turn on DEBUG for `ru.driics.aitrade.infra.exchange` to see the breakdown.)
- If the journal is on, the analytics API (`/api/analytics/summary|trades|by-symbol|equity-curve`) gives a
  live realized-performance read.

---

## Capture bills for B0 (do this on a flat-to-flat day)

Once the soak has produced real fills, reconcile the daily-loss cap's PnL input against OKX's own oracle.
**Capture on a flat-to-flat intraday day** (no position carried in from yesterday, none open at capture, but
≥1 opened **and** closed during the day) — the two accounting views only agree under that condition.

```powershell
# Safe one-shot: forces auto-execute off + pushes the scheduler 24h out, so NO order is placed while capturing.
.\gradlew.bat bootRun --args='--spring.profiles.active=capture-okx'
# writes ./data/okx-capture/{bills.json, positions-history.json, capture-meta.json} then stops the JVM

.\gradlew.bat test --tests "*OkxPnlReconciliationFixtureTest*" --info
# reads ./data/okx-capture/reconciliation-report.txt
```

Then follow `docs/pnl-reconciliation.md` step 4: aim for `bills.pnl − oracle ≈ 0`. The one open question from
the 2026-06-01 audit — does `bills.pnl` already include trading fees, or does the cap under-report losses by
the fee total? — is answered here. If a real gap holds across **≥2 flat-to-flat days**, adjust
`OkxExchangeAdapter.realizedPnlContribution()` and lock a tolerance.

---

## Exit criteria (R1 + B0 done)
- The loop ran **multiple days** without crashing or wedging (WS recovers, no runaway spend, journal healthy).
- **Shorts and the risk cap were observed working** under real conditions.
- **≥2 flat-to-flat days** of bills captured and reconciled → `bills.pnl − oracle ≈ 0` (or the knob fixed so
  it is) with a committed tolerance. → the daily-loss cap is now trustworthy for LIVE.

---

## Safety & abort
- PAPER = OKX **demo** = **no real funds**. The 3-gate LIVE refusal still stands; you cannot accidentally trade
  real money from this config.
- The **kill-switch** (`./data/kill-switch.json`) and **daily-loss cap** apply in PAPER too — use them to halt.
- To stop: `Ctrl+C` (graceful shutdown), or trip the kill-switch via `RiskController`.

See also: `docs/pnl-reconciliation.md` (B0), `docs/phase3-streaming-runbook.md` (optional WS entry-price),
and the SIMULATION/PAPER/LIVE notes in the project memory.

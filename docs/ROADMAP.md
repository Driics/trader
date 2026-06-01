# aiTrader — Prioritized Roadmap

_Originally generated 2026-05-25. **Refreshed 2026-05-31** after a code-verified state audit — the original
snapshot was stale (it claimed empty tests, a PnL stub, and no journal/CI; all four are now false). Branch:
`feature/mr-29`._

_**Update 2026-06-01:** shipped a per-trade risk cap, a short-enabling prompt fix, and market-data
reliability hardening (see §1); R0 is now in progress — `feature/mr-29` is pushed and PR
[#13](https://github.com/Driics/trader/pull/13) → `master` is open. The B0 reconciliation runbook for R1
is `docs/paper-soak-runbook.md`._

## 1. Current-State Snapshot

**Stack:** Kotlin 2.2 / JDK 21 / Spring Boot 3.5.7 / Ktor 3.3 client, coroutines, Koog AI starter 0.5.1
(OpenRouter), Resilience4j, Micrometer + OTel + Prometheus, Caffeine cache, Loki/Logback JSON logging,
Liquibase + Postgres (opt-in trade journal).

**Architecture (hexagonal / ports-and-adapters):**
- `domain/` — pure model, ports (`MarketDataPort`, `StreamingMarketDataPort`, `TradingPort`, `AiAnalysisPort`,
  `PromptOutputPort`, `TradeJournalPort`, `DecisionLogSink`), policies (`OrderSizingPolicy`,
  `IndicatorCalculator`, `PromptBuilder/Formatter`), and sub-domains `risk/`, `strategy/`, `backtest/`,
  `journal/`, `types/`.
- `application/` — `UpdateCycleOrchestrator` (7-stage cycle) + use-cases (`BuildPrompt`, `AnalyzePrompt`,
  `ExecuteAiDecisions`) + AI glue (`AiSchemaValidator`, `IdempotencyService`, `ConfidenceCalibrator`,
  `ActionGuard`, `AiBudgetLimiter`) + risk (`RiskGate`, `KillSwitchState`, `KillSwitchStore`).
- `infra/` — OKX REST + WS adapters, Koog/OpenRouter AI adapter, Caffeine cache (+ opt-in Redis no-op),
  JDBC trade journal, prompt output, WS frame recording.
- `service/okx/` + `config/` — REST/Ktor wiring, auth, properties, `ApplicationWiring`, `TradingModeGuard`.
- `controller/` — Spring MVC surface (`TradingSystemController`, `RiskController`) + validation + global handler.

**Working / shipped (verified 2026-05-31, all on `feature/mr-29`):**
- **Test suite is real and green.** 58 test files; `gradlew test` passes locally.
- **Architecture hardening complete (13/13).** All findings in `architecture-refactor-roadmap.md` are resolved
  or formally closed (D1: the `KillSwitchStore` port moved to `domain/ports`; D2: distributed Redis scoped out
  with a fail-fast guard). Risk reads fail **closed**; OKX calls return a typed `OkxCallOutcome`; the position
  cap is enforced per-order atomically; the dedup hash advances only on full-cycle success; the cycle runs off
  the Spring scheduler thread on a dedicated dispatcher.
- **Risk gates (X2).** Kill-switch (persisted via `KillSwitchStore`), daily-loss cap (fail-closed),
  per-order concurrent-position cap.
- **Trade journal (X1).** Opt-in Postgres (Supabase) + Liquibase persistence of orders/fills/PnL snapshots.
  Default OFF, fail-safe (a DB outage never blocks a trading cycle).
- **Daily-PnL gate reads real data.** `getTodaysRealizedPnlUsd` sums `/account/bills` (no longer a ZERO stub);
  read logic (UTC window, pagination, loss-sign, fail-closed) is locked by `OkxExchangeAdapterPnlTest`.
- **Backtest engine.** Strategy seam, deterministic no-look-ahead engine, walk-forward / OOS validation,
  param sweep, AI record→replay (`RecordedAiStrategy`).
- **Phase-3 streaming entry price.** Prefer fresh WS price over REST for sizing, with REST fallback on
  stale/divergent ticks. Default OFF; parity probe + runbook (`docs/phase3-streaming-runbook.md`).
- **AI multi-pair hardening.** Per-instrument leverage cap enforced; instruments config-driven via
  `InstrumentResolver`; `OrderSide` typed end-to-end.
- **CI/CD.** `.github/workflows/ci-cd.yml` — build + test + bootJar + Docker build on push/PR to master.
- **Safety defaults.** `auto-execute` OFF, `demo-mode` ON; LIVE (real funds) startup refused unless
  `demo-mode=false` AND `paper=false` AND `confirm-live=true`.

**Added 2026-06-01 (all on `feature/mr-29`):**
- **Per-trade risk cap.** Position size is capped at `trading.risk.max-risk-per-trade-pct` of equity (default
  2%): `risk_usd` is authoritative and the model's self-reported `quantity` is advisory (clamped, never above).
  A missing stop / zero equity skips the trade. Shared by the live path **and** the backtest engine, so a
  backtest reflects production sizing. (Closed a real gap: a single AI trade could otherwise risk 30%+ of the
  book — observed 34% before the cap.)
- **Short-enabling prompt fix.** The system prompt now frames perpetual futures and that `sell` opens a SHORT;
  the bot was previously long-only and lost money fighting downtrends. ⚠️ **Live short behaviour is unobserved
  — validate it in the R1 paper soak before any live funds.**
- **Market-data reliability.** The per-symbol fetch timeout is configurable (`okx-timeouts.currency-fetch`,
  default 15s > the 10s candle timeout it used to undercut) and an empty-data guard skips the AI call (no
  spend, retries next cycle) when a whole snapshot fails; failed (zero-price) symbols are dropped from the
  prompt on partial failure.
- **Candle WS endpoint fix** (`/ws/v5/business`) and an **offline model-comparison harness**
  (`ModelComparisonHarnessTest`) that ranks recorded decision logs by P&L over the same candles.

**Remaining gaps (the path to 1.0 — see §2):**
- `feature/mr-29` is **133 commits ahead of `master`** (whose tip is ~7 months old) and **pushed**, with PR
  [#13](https://github.com/Driics/trader/pull/13) → `master` open (R0 in progress). The release still cannot be
  tagged until the PR is reviewed and merged.
- The daily-loss cap's `bills.pnl`-field interpretation has **never been reconciled against live OKX**
  (`docs/pnl-reconciliation.md`). The cap is built and fail-closed but rests on this one unverified input.
- No multi-day **real-conditions paper soak** — the 15-min autonomous loop is unproven over time.
- No `README.md`; version still `0.0.1-SNAPSHOT`; secrets in `.env` (no Vault/Doppler); no Grafana
  dashboards-as-code under `infra/monitoring/`.

---

## 2. Roadmap

### ✅ Shipped on `feature/mr-29`
| Goal | Status |
|------|--------|
| X1 — Trade-journal persistence (orders/fills/PnL, opt-in) | ✅ shipped |
| X2 — Risk hard gates (kill-switch + persistence, daily-loss, per-order position cap) | ✅ shipped |
| X2.a — Real daily-PnL read via `/account/bills` (replaces ZERO stub) | ✅ shipped (semantics unverified — see B0) |
| X2.b — De-duplicate per-cycle market load (P2) | ✅ shipped |
| X5 — Backtest / replay harness | ✅ shipped |
| Phase 3 — Streaming entry price (flag, parity probe, runbook) | ✅ shipped (default OFF) |
| Architecture hardening (S1–S9, P1, P2, D1, D2, D3) | ✅ 13/13 resolved or formally closed |
| D1 — `KillSwitchStore` driven port relocated to `domain/ports` | ✅ shipped (tests green) |
| D2 — misleading Redis stub removed; distributed cache scoped out (fail-fast guard) | ✅ shipped (tests green) |
| CI/CD pipeline (build + test + jar + docker) | ✅ shipped |
| Per-trade risk cap (% of equity; risk_usd authoritative, live + backtest) | ✅ shipped (2026-06-01) |
| Short-enabling prompt fix (futures framing; bot can now short) | ✅ shipped (2026-06-01) — validate live in R1 |
| Market-data reliability (configurable timeout + empty-data guard + prompt filter) | ✅ shipped (2026-06-01) |
| Candle WS on `/ws/v5/business` + offline model-comparison harness | ✅ shipped (2026-06-01) |

### 🎯 Now — gates to 1.0.0 (production = trustworthy with live funds)
| # | Goal | Why | Effort |
|---|------|-----|--------|
| R0 | **Land `feature/mr-29` on `master`** — _in progress_ | A 1.0 tag must point at master; master is ~7 months stale. Pushed; PR [#13](https://github.com/Driics/trader/pull/13) open. Remaining: review + merge. | S |
| R1 | **Multi-day OKX-demo paper soak** — see `docs/paper-soak-runbook.md` | One action, three payoffs: proves the autonomous loop survives real conditions (WS reconnects, scheduler, AI cost/latency drift, journal under load) — which no unit test covers; **observes the new live SHORT behaviour + per-trade risk cap** under load; **and** generates the real `bills` the PnL reconciliation (B0) needs. | M |
| B0 | **Close the realized-PnL reconciliation loop** (`docs/pnl-reconciliation.md`) | Confirm `bills.pnl − oracle ≈ 0` across ≥2 flat-to-flat days, then lock a tolerance. Until green, the daily-loss cap cannot be trusted with live funds. | M |
| R2 | **Write `README.md`** | Operator quickstart + the SIMULATION/PAPER/LIVE safety matrix. Table stakes for any 1.0. | S |
| R3 | **Bump version off `0.0.1-SNAPSHOT`** on release | — | S |

### 📦 Next — release polish (1.0 or fast-follow 1.1)
- Grafana dashboards-as-code + alerts (cycle duration, WS disconnects, order-reject rate, AI failure rate)
  under `infra/monitoring/` (X4). **M**
- Secret management: move OKX/OpenRouter keys to Vault/Doppler out of `.env` (L6). **S**
- AI cost/latency budget hardening: circuit breaker on OpenRouter spend (X3). `ai-budget-per-minute` exists. **M**

### 🗄️ Later — backlog
- L1 Distributed Redis L2 cache for instrument metadata — only if multi-instance becomes a requirement.
  Currently scoped out (D2): the `RedisCacheAdapter` port exists with a `NoOp` default, and `redis.enabled=true`
  fails fast until a real adapter (`spring-boot-starter-data-redis`) is implemented. **M**
- L2 Multi-exchange abstraction (Binance/Bybit behind existing ports). **L**
- L3 Strategy plug-in API: swap `PromptBuilder` for non-AI rule engines / ensemble. **M**
- L4 Web UI / control panel beyond the controllers (positions, manual override). **M**

---

## 3. Top 3 Risks / Tech-Debt Hot Spots

1. **The daily-loss cap rests on an unverified money-measurement assumption.** The cap is built, tested, and
   fail-closed, but `realizedPnlContribution()`'s reading of OKX `bills.pnl` has never been confirmed against
   live OKX. A wrong sign or omitted-fee gap means it could fail to halt on a real losing day. **Resolve via
   R1 + B0 before any live funds.**
2. **All work is on an unmerged 133-commit branch; `master` is ~7 months stale.** Every "shipped" item above is
   invisible from master. Landing is now in progress (PR [#13](https://github.com/Driics/trader/pull/13) open) —
   review + merge it; a release can't be cut otherwise.
3. **No sustained real-conditions run.** Unit tests are green, but the multi-hour/multi-day autonomous loop
   (WS reconnect behaviour, scheduler under load, AI cost drift, journal write volume) has not been observed
   end-to-end. The paper soak (R1) is the cheapest way to surface what tests can't.

---

## 4. Suggested Immediate Next Action (today)

Kick off a **multi-day OKX-demo paper soak** (R1) — full steps in `docs/paper-soak-runbook.md`. It is the
single highest-leverage move: it exercises the full autonomous loop under real conditions, **validates the
new live SHORT behaviour + per-trade risk cap** (shipped 2026-06-01, never run live), *and* produces the
`/account/bills` data that unblocks the PnL reconciliation (B0) — the one verification gate standing between
"feature-complete on paper" and "trustworthy with live funds." In parallel, merge PR
[#13](https://github.com/Driics/trader/pull/13) to land `feature/mr-29` on `master` (R0) so the soak's
outcome can be released.

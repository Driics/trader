# aiTrader — Prioritized Roadmap

_Generated 2026-05-25 from a read-only audit. Branch: `feature/mr-29`._

## 1. Current-State Snapshot

**Stack:** Kotlin 2.2 / JDK 21 / Spring Boot 3.5.7 / Ktor 3.3 client, coroutines, Koog AI starter (OpenRouter), Resilience4j, Micrometer + OTel + Prometheus, Caffeine cache, Loki/Logback JSON logging.

**Architecture (hexagonal-ish layering):**
- `domain/` — pure model + ports (`MarketDataPort`, `StreamingMarketDataPort`, `TradingPort`, `AiAnalysisPort`, `PromptOutputPort`) and policies (`OrderSizingPolicy`, `IndicatorCalculator`, `PromptBuilder/Formatter`, `TradingMetricsService`).
- `application/` — `UpdateCycleOrchestrator` (main loop) + use-cases + AI glue.
- `infra/` — OKX REST + WS adapters, Caffeine cache, prompt output, AI adapter.
- `service/okx/` + `config/` — REST/Ktor wiring, auth, properties.
- `controller/` — Spring MVC surface + validation + global exception handler.

**Working:** Spring boot app builds, Dockerfile multi-stage, docker-compose stack (otel-collector, jaeger, prometheus, grafana, loki, app), Resilience4j tuned per OKX endpoint class, structured logging documented.

**In flight (uncommitted on `feature/mr-29`):** refactors to `OkxExchangeAdapter`, `OkxStreamingAdapter`, `OkxClientBase`, `UpdateCycleOrchestrator`, `TradingMetricsService`, edits to `prompt.txt`, and a new `logback-spring.xml`. Pattern (per recent commits "WS optimization", "metrics/logging", "dynamic tags") = a streaming + observability hardening pass.

**Notable gaps:** `src/test/kotlin/ru` is effectively empty; no `ROADMAP.md`/`TODO`; no CI workflows visible at repo root (`.github/` is untracked); `auto-execute: true` in `application.yml` but compose sets `TRADING_AUTO_EXECUTE=false` (drift); Redis L2 stubbed but disabled; no persistence layer for trades/PnL.

---

## 2. Roadmap

### Now — this week
| # | Goal | Why | Effort | Owner |
|---|---|---|---|---|
| N1 | Land the `feature/mr-29` WS/metrics refactor: split commit, write changelog, open PR | 6 modified files sitting uncommitted = merge risk + lost context | S | executor |
| N2 | Reconcile `auto-execute`/`demo-mode` defaults across `application.yml` vs `docker-compose.yml` | Prevents accidental live trading on a $0.12 account | S | backend-architect |
| N3 | Add a smoke test module (`OkxExchangeAdapterTest`, `IndicatorCalculatorTest`, `OrderSizingPolicyTest` with MockK + WireMock — deps already present) | Test tree is empty; refactors are unverified | M | qa |
| N4 | Commit `logback-spring.xml` + wire `LOG_DIR`/profile activation; verify Loki appender ships locally | Logging doc references infra that isn't checked in | S | devops |

### Next — 2–4 weeks
| # | Goal | Why | Effort | Owner |
|---|---|---|---|---|
| X1 | Persistence for orders, fills, PnL snapshots (Postgres + Flyway, behind a `TradeJournalPort`) | Currently no audit trail; can't compute true return | L | backend-architect |
| X2 | Risk-management hard gates (max daily loss, max position, kill-switch endpoint) wired into `UpdateCycleOrchestrator` | Prompt allows leverage 5–40; needs out-of-band brake | M | executor |
| X3 | AI cost/latency budget enforcement via `ai-budget-per-minute` + circuit breaker on OpenRouter | Multi-key rotation exists but no spend cap visible | M | executor |
| X4 | Grafana dashboards + alerts (cycle duration, WS disconnects, order reject rate, AI failure rate) committed under `infra/monitoring/` | Prometheus is scraping but no dashboards-as-code | M | devops |
| X5 | Backtest/replay harness using recorded WS frames + `IndicatorCalculator` | Enables strategy iteration without burning real capital | L | backend-architect |

### Later — backlog
- L1 Enable Redis L2 cache for instrument metadata (toggle already exists). **S**, devops.
- L2 Multi-exchange abstraction (Binance/Bybit adapters behind existing ports). **L**, backend-architect.
- L3 Strategy plug-in API: swap `PromptBuilder` for non-AI rule engines / ensemble. **M**, architect.
- L4 Web UI / control panel beyond `TradingSystemController` (positions, manual override). **M**, executor + designer.
- L5 GitHub Actions: build + test + container publish + Trivy scan. **M**, devops.
- L6 Secret management: move OKX/OpenRouter keys to Vault/Doppler instead of `.env`. **S**, devops.

---

## 3. Top 3 Risks / Tech-Debt Hot Spots

1. **Zero test coverage on a money-moving system.** Test scaffold is in place (MockK, WireMock, JUnit5) but `src/test/kotlin/ru` is empty. Every refactor is a blind change. **Fix before N1 merges.**
2. **Config drift between local YAML and container env.** `auto-execute: true` (yml) vs `TRADING_AUTO_EXECUTE=false` (compose), `demo-mode` defaulted true but easy to flip; no startup assertion logs the *effective* mode. One bad env var = live orders.
3. **No durable trade journal.** Account state, PnL, and orders only live in OKX + in-memory; restarts lose context, and the "Current Total Return" metric in `prompt.txt` cannot be trusted across restarts.

---

## 4. Suggested Immediate Next Action (today)

Open the `feature/mr-29` PR cleanly:
1. `git diff --stat` to confirm the 6 modified files are the WS/metrics pass.
2. Stage `src/main/resources/logback-spring.xml` (currently untracked) with the rest of the logging changes.
3. Split into two commits — `refactor(okx-ws): …` and `chore(observability): logback + metrics tags` — push, and open the PR with a checklist that includes **N3 (add smoke tests)** as a blocker for merge.

This unblocks Now-bucket items N1, N3, N4 in one motion and protects against Risk #1 before the diff grows further.

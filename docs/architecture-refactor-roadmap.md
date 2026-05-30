# Architecture Refactoring Roadmap — aiTrader

> Status: proposed for team review · Scope: confirmed/verified findings only · Branch: `feature/mr-29`

## 1. Executive Summary

aiTrader is a Kotlin + Spring Boot AI futures-trading system (`ru.driics.aitrade`) in a hexagonal / ports-and-adapters layout. A single Spring `@Scheduled` method (`PromptSchedulerService`, `fixedDelay` default 15min, 5s initial delay) wraps `runBlocking { orchestrator.runOnce() }`, which drives a 7-stage OpenTelemetry-traced pipeline: BUILD_PROMPT → prompt-hash dedup → AI_CALL → PARSE_RESPONSE → GUARD_NORMALIZE → EXECUTE_ORDERS (RiskContext + RiskGate, then `ExecuteAiDecisionsUseCase`). The domain layer holds pure ports and policies; infra adapters implement OKX REST/WebSocket, the Koog/OpenRouter LLM client, and caches (Redis is a no-op placeholder). Risk (`RiskGate`, `KillSwitchState`) currently lives under `application/risk`.

**Top themes:**

- **Stability (the dominant theme, and where the real risk lives).** The EXECUTE-stage risk inputs **fail open**: a PnL or positions read failure silently disables the daily-loss and concurrent-position caps exactly when the exchange is degraded — and `getTodaysRealizedPnlUsd` is a stub returning `ZERO`, so the daily-loss cap is inert even in steady state. The kill-switch is in-memory only (a manual pause is lost on restart). `RiskContext` is snapshotted once per cycle, so a concurrent batch can collectively exceed `maxConcurrentPositions`. Dedup state (`lastPromptHash`) advances on *build* success, not *cycle* success, silently suppressing trading after a transient downstream failure. The OKX call wrapper collapses all failures to `null`, erasing timeout-vs-rejection.
- **Performance.** The whole cycle (including the LLM round trip with retries + linear backoff) runs under `runBlocking` on the single Spring scheduler thread. Market state is loaded redundantly (3+ times) per executing cycle, including one load whose only output is a count that is structurally always zero.
- **Developer Experience.** Pure risk policy lives in `application/risk` while sibling pure policies live in `domain/services` (inconsistent home for safety invariants); a dead `getKillSwitchState()` getter leaks a collaborator through the orchestrator surface; stubbed adapters (PnL, Redis) make several map-flagged behaviors intended-but-unimplemented.

> **Note on this list:** the source findings contained three near-duplicate entries (the `runBlocking`/scheduler finding appeared twice; the redundant-market-load finding appeared three times). They are merged below into single canonical entries, leaving **11 distinct findings**.

## 2. Prioritized Findings

Priority uses the **verifier's `adjustedRisk`** (not the headline severity), since several items were downgraded on verification (noted inline). Only items with `safeToAutoApply: true` are eligible for Phase A.

| # | Finding | Dim | Adj. Risk | Effort | Refactor Risk | Auto-apply | Key files |
|---|---------|-----|-----------|--------|---------------|------------|-----------|
| S1 | EXECUTE-stage risk inputs **fail open** (PnL→ZERO, positions→0) + PnL stub makes daily-loss cap inert | stability | **high** | medium | medium | no | `UpdateCycleOrchestrator.kt`, `OkxExchangeAdapter.kt` |
| S2 | `KillSwitchState` in-memory only — manual/auto trip lost on restart | stability | medium (was high) | medium | high | no | `KillSwitchState.kt`, `ApplicationWiring.kt` |
| S3 | `RiskContext` snapshotted once/cycle — concurrent batch can exceed `maxConcurrentPositions` | stability | medium (was high) | medium | medium | no | `ExecuteAiDecisionsUseCase.kt`, `UpdateCycleOrchestrator.kt`, `RiskGate.kt` |
| S4 | OKX call wrapper swallows all failures to `null` (timeout ≡ rejection ≡ crash) | stability | medium | medium | medium | no | `service/okx/OkxTradingClient.kt` |
| S5 | `lastPromptHash` advances on build success — downstream failure skips next identical cycle | stability | medium | small | medium | no | `UpdateCycleOrchestrator.kt` |
| P1 | Whole cycle (incl. LLM call) runs under `runBlocking` on single scheduler thread | performance | medium (was high) | small–med | medium | no | `PromptSchedulerService.kt`, `AnalyzePromptUseCase.kt` |
| P2 | Redundant market-state loads (3+×) per executing cycle; one count is structurally always 0 | performance / dx | medium | medium | medium | no | `BuildPromptUseCase.kt`, `UpdateCycleOrchestrator.kt`, `ExecuteAiDecisionsUseCase.kt` |
| S6 | DEMO mode mutates calibration/cooldown state and emits `order_placed` events | stability | low | small | low | no | `ExecuteAiDecisionsUseCase.kt` |
| S7 | AI retry classification uses brittle message-substring matching | stability | low | small | medium | no | `AnalyzePromptUseCase.kt` |
| S8 | Idempotency `signalKey` floor-window vs cache sliding-TTL mismatch (boundary duplicates) | stability | low | small | low | no | `application/ai/IdempotencyService.kt`, `application/ai/common/TimeBasedCache.kt` |
| S9 | Validation rejection reason → high-cardinality, truncated metric tag | stability | low | small | low | no | `AiSchemaValidator.kt`, `UpdateCycleOrchestrator.kt` |
| D1 | Risk/kill-switch pure policy lives in `application/risk`, not `domain` | dx | low | medium | medium | no | `application/risk/RiskGate.kt`, `KillSwitchState.kt` |
| D2 | Stubbed adapters (PnL = ZERO, Redis = no-op) make behaviors unauditable | dx | low | medium | low | no | `OkxExchangeAdapter.kt`, `infra/cache/RedisCacheAdapter.kt` |
| **D3** | **Dead `getKillSwitchState()` getter on orchestrator (0 callers)** | dx | low | small | low | **yes** | `UpdateCycleOrchestrator.kt`, `controller/RiskController.kt` |

> **Phase A reality check:** of all findings, **exactly one** (D3) is marked `safeToAutoApply: true` by the verifier. Every other item is `safeToAutoApply: false` and requires human design judgment. Phase A is therefore a single mechanical deletion; see §6.

---

## 3. Performance

### P1 — Whole cycle (incl. LLM call) runs under `runBlocking` on the single Spring scheduler thread

**Problem.** `PromptSchedulerService.scheduledUpdate()` is `@Scheduled(fixedDelayString=...)` and wraps `runBlocking { orchestrator.runOnce() }` directly on the scheduler thread, with no dedicated dispatcher. `runOnce()` drives the entire pipeline including the LLM round trip, which can hold the thread for `aiTimeoutMs × (aiMaxRetries+1)` plus backoff.

**Evidence.**
- `PromptSchedulerService.kt:29–45` — `@Scheduled(fixedDelayString="\${ai.trade.scheduler.interval-ms:900000}", initialDelayString="\${...:5000}")`, body `runBlocking { orchestrator.runOnce() }`.
- `AnalyzePromptUseCase.kt` — `repeat(maxRetries+1)` loop (≈ line 53), each attempt `withTimeout(timeoutMs)` (≈ line 59), backoff `delay(500L * (attempt+1))` (≈ line 115). **Corrected fact:** backoff is *linear* (500ms / 1s / 1.5s), **not** exponential, and the original "lines 30/48-50/60" citations were off — cite the method, not those line numbers.
- No `TaskScheduler`/`ThreadPoolTaskScheduler`/`SchedulingConfigurer`/`@EnableAsync` override exists, so Spring's default single-thread scheduler applies.

**Caveats from verification.** Because the method uses `fixedDelay` (not `fixedRate`), cycles cannot overlap by design — a slow call only delays the *next* tick. The genuine harm is starving *other* `@Scheduled` tasks sharing the pool; grep found **no other `@Scheduled` methods today**, so the real-world blast radius is currently small. Severity adjusted high → medium accordingly.

**Recommended change.** Bridge `@Scheduled` to the suspend pipeline via a dedicated single-threaded dispatcher (e.g. a private `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`), or move `runOnce` onto an application `CoroutineScope` and have the scheduler only trigger/await with a single-flight `AtomicBoolean` guard. **Critical:** if you offload off `fixedDelay`, you lose its natural non-overlap guarantee, so the single-flight guard becomes mandatory.

**Risk / Effort.** Refactor risk: medium (alters threading/cancellation/shutdown semantics; needs lifecycle management of the executor). Effort: small–medium. Not auto-applicable.

### P2 — Redundant market-state loads (3+×) per executing cycle

**Problem.** `loadMarketState` is invoked multiple times within a single executing cycle, rebuilding the same per-symbol snapshot and producing potentially divergent views feeding prompt-build, risk-context, and execution. One of these loads exists solely to compute a count that can never be non-zero.

**Evidence.**
- `BuildPromptUseCase.execute` — `marketDataPort.loadMarketState(symbols)` (≈ line 12–24).
- `UpdateCycleOrchestrator` `buildRiskContext` (from `stageExecute`) — loads again purely to compute `count { it.position != null }`. **Load-bearing fact:** `OkxExchangeAdapter.loadSnapshot` hardcodes `position = null` (positions are loaded separately via `PositionPort`), so this count is **structurally always 0** — a full N-symbol load whose only output is a meaningless count, and `RiskContext.openPositions` is effectively dead/always-zero.
- `ExecuteAiDecisionsUseCase` — loads a third time under `withTimeout(30s)`; the strongest verification found this call sits **inside the per-decision loop** (`executeOne`), so the duplication is **>3×**, not exactly 3×.
- Matches roadmap `X2.b` ("dedupe market load").

**Recommended change.** Load the snapshot once at the top of `executePipeline` and thread the immutable `MarketState` down to BuildPrompt, the RiskContext build, and ExecuteAiDecisions. Remove the `stageExecute` position-count load (or source open positions from a real positions endpoint). `BuildPromptUseCase` already returns `marketState` in `PromptResult` and that result is already threaded into `stageExecute`, so the plumbing is partly present.

**Design tension (why this is not mechanical).** Execution currently sizes/prices orders off the *freshest* snapshot read at execution time. Collapsing to one snapshot taken at prompt-build time deliberately shifts order sizing, price selection, and equity onto **staler** data — a freshness-vs-consistency tradeoff with financial stakes that needs a human decision.

**Risk / Effort.** Refactor risk: medium (multi-file signature change across 3 files; touches risk-gate input semantics and order-pricing freshness). Effort: medium. Not auto-applicable.

---

## 4. Stability

### S1 — EXECUTE-stage risk inputs fail OPEN (the true #1) — daily-loss & position caps silently disabled during an outage

**Problem.** When OKX is degraded — precisely when risk controls matter most — the daily-loss and concurrent-position caps go silent. Compounded by the PnL stub, one of the two caps is non-functional even in steady state.

**Evidence.**
- `UpdateCycleOrchestrator.kt:273–284` (`stageExecute`, gated by `config.autoExecute`): a PnL read `TradeResult.Failure` logs and defaults to `BigDecimal.ZERO` (comment literally says "fail-open"); a positions `loadMarketState()` failure (`runCatching`) defaults to `0` (comment "fail-open").
- `RiskGate.kt:33–39`: blocks only when `todaysRealizedPnlUsd <= -maxDailyLossUsd` → with PnL = ZERO, `0 <= -cap` is false → cap does **not** fire. `RiskGate.kt:41–46`: blocks when `openPositionsCount >= maxConcurrentPositions` → with count = 0, never fires. The auto-trip path that would set the kill-switch (`RiskGate.kt:36`) is itself defeated because PnL reads ZERO.
- `OkxExchangeAdapter.kt:157–162`: `getTodaysRealizedPnlUsd` is a hardcoded STUB returning `BigDecimal.ZERO` (real `/account/bills` deferred to `X2.a`), corroborated by `docs/ROADMAP.md:45`.

**Recommended change.** Make risk-path reads **fail-CLOSED**: if PnL or open-positions cannot be read, treat as `Block` (or reuse the last-known snapshot), or at minimum gate auto-execution off for the cycle. Track the PnL stub (**X2.a**) as a hard blocker for trusting the daily-loss cap.

**Risk / Effort.** Refactor risk: medium. Adjusted risk: **high** (genuine safety hole). The existing fail-open is documented as intentional X2 design, so reversing it needs explicit human sign-off; a naive reversal could wedge the trading loop on transient outages. Effort: medium. Not auto-applicable.

### S2 — KillSwitchState is in-memory only

**Problem.** All safety state lives in a single `AtomicReference<KillSwitchSnapshot>` with no persistence port. A MANUAL operator pause or an AUTO_DAILY_LOSS trip evaporates on restart, and the scheduled loop resumes ~5s after boot.

**Evidence.**
- `KillSwitchState.kt:18` — single private `AtomicReference<KillSwitchSnapshot>`; `trip()` (40–59) and `clear()` (61–65) only mutate the atomic; the `@Component` takes only a `Clock`. No `@PostConstruct` rehydration, no durable store.
- `ApplicationWiring.kt` — wires `KillSwitchState` into `RiskGate` and `UpdateCycleOrchestrator`.

**Caveats.** Adjusted high → medium: AUTO_DAILY_LOSS trips auto-clear at UTC-midnight anyway; trading is single-instance via `@Scheduled` (cross-instance coordination is speculative for current deployment); an AUTO condition that still holds re-trips next cycle. The residual real risk is the **manual-pause-lost-on-restart** scenario.

**Recommended change.** Introduce a `KillSwitchStore` port (file/DB/Redis), read in `@PostConstruct` to rehydrate, written on every `trip()`/`clear()`. Decide multi-instance semantics and store-unavailable failure handling explicitly.

**Risk / Effort.** Refactor risk: high (backend choice, serialization, rehydration of auto-clearing trips, failure handling). Effort: medium. Not auto-applicable.

### S3 — RiskContext snapshotted once per cycle → batch can exceed `maxConcurrentPositions`

**Problem.** One `RiskContext` with a pre-cycle `openPositionsCount` is shared across concurrent evaluations, so N symbols all observe the same sub-cap count and all pass — the cap is a per-cycle-entry gate, not a per-order gate.

**Evidence.**
- `UpdateCycleOrchestrator` (≈ 237–240): `riskContext = buildRiskContext(openPositions.size)` (single snapshot, immutable `openPositionsCount`).
- `ExecuteAiDecisionsUseCase`: `executePlan` runs under `flatMapMerge(concurrency = maxConcurrentSymbols)`, passing the **same** `riskContext` to every plan; `riskGate.evaluate` then `placeOrder`, never incrementing the snapshot.
- `RiskGate.kt:41`: `if (ctx.openPositionsCount >= ctx.maxConcurrentPositions) reject` against the frozen value.

**Caveats.** Adjusted high → medium: over-shoot is bounded by `maxConcurrentSymbols` (default 4) and only on cycles starting near the cap.

**Recommended change.** Track an in-cycle placed-count (`AtomicInteger` incremented on each successful placement) added to `openPositionsCount` before each `evaluate`, or serialize gate+placement. **Caution:** a naive read-then-increment reintroduces a TOCTOU race; the check-then-place must be an atomic critical section. This changes `RiskGate.evaluate`'s contract from a pure function over an immutable context to one consulting live state.

**Risk / Effort.** Refactor risk: medium (concurrency redesign). Effort: medium. Not auto-applicable.

### S4 — OKX call wrapper swallows all failures to `null`

**Problem.** `executeOkxCall` returns `null` for timeout, 4xx/5xx, and generic exceptions alike, so a network timeout on order placement is indistinguishable from a clean rejection — undermining safe retry/idempotency decisions.

**Evidence.**
- `OkxTradingClient.kt` (`executeOkxCall` ≈ line 151, catches 184–196): three catch arms each return `null` — `TimeoutCancellationException`, `ClientRequestException`, generic `Exception`. `setLeverage` collapses with `?: false` (≈ line 84); `placeMarketOrderWithAttach` returns the `null` directly.
- Consequence: `setLeverage` returning `false` on a transient error makes `executePlan` skip the trade as a business "Failed to set leverage", masking infra errors. **Verification nuance:** metrics *are* differentiated via a Micrometer `status` tag (timeout/http_4xx/http_5xx/error/ok), but the distinction is lost in the *return value* callers branch on — and `@Retry`/`@CircuitBreaker` never see the swallowed exceptions.

**Recommended change.** Propagate a typed sealed outcome (`success` / `rejected-by-exchange` / `timeout-unknown` / `transport-error`) instead of `null`, so retry, idempotency, and metrics can make safe decisions.

**Risk / Effort.** Refactor risk: medium (new sealed type; rewrite every return path + `responseMapper` contract + caller branches; reconcile with resilience4j). Effort: medium. Not auto-applicable.

### S5 — `lastPromptHash` advances on build success, not full-cycle success

**Problem.** The dedup hash is committed the first time a prompt is seen, *before* AI/parse/guard/execute run. If a later stage fails, the hash has already advanced; on the next cycle an identical prompt (common in a flat market) is treated as "unchanged" and the whole cycle is skipped silently.

**Evidence.**
- `UpdateCycleOrchestrator.kt:376–384` (`isPromptUnchanged`) calls `lastPromptHash.set(hash)` then returns; invoked at line 139, before AI_CALL (146), PARSE (149), GUARD (152), EXECUTE (157). Each later stage early-returns on failure and never touches `lastPromptHash`. It is set only at line 381 and never reset anywhere. The skip path returns `UpdateCycleResult.skipped` with `success=true` (silent).

**Recommended change.** Commit `lastPromptHash` only after a fully successful cycle (move the set into the success path), or reset it to `null` when any post-build stage fails.

**Design questions to resolve.** Does the `autoExecute=false` manual-mode path (`stageExecute` returns `success(0)`) count as a "successful cycle" for dedup commit? Getting this wrong either re-runs AI on every flat cycle (cost/rate-limit) or fails to skip when intended.

**Risk / Effort.** Refactor risk: medium (touches core control flow + atomic dedup state). Effort: small. Not auto-applicable.

### S6 — DEMO mode mutates calibration state and emits `order_placed` events

**Problem.** In `demoMode`, simulated success still runs the full side-effecting path: cooldown, idempotency, and production business events — polluting calibration/cooldown timing and `order_placed` metrics with fake fills.

**Evidence.** `ExecuteAiDecisionsUseCase.kt:254–263` returns `handleSuccessfulOrder(... clOrdId="DEMO-...")`; `handleSuccessfulOrder` (284–305) unconditionally calls `confidenceCalibrator.recordTrade` (291), `idempotencyService.recordSignal` (292), and `BusinessEventLogger.orderPlaced` (294–305). No demo flag, no guard.

**Recommended change.** Short-circuit before the side effects in demo mode, or pass a demo flag that skips `recordTrade`/idempotency and tags the event as simulated.

**Caveat.** It is plausible the team intentionally exercises the full guardrail path in demo for end-to-end testing — confirm intended demo semantics first.

**Risk / Effort.** Refactor risk: low (demo/paper config only; no real orders). Effort: small. Not auto-applicable (design decision on demo semantics).

### S7 — AI retry classification uses brittle message-substring matching

**Problem.** Retryability is decided from free-text exception/response messages (`timeout`, `rate limit`, `503`, `502`, `500`, `connection`, `reset`). If the provider changes wording, transient 5xx/429 silently become non-retryable; matching `500` as a substring can false-match unrelated numbers (e.g. `500ms`, prices, token counts).

**Evidence.** `AnalyzePromptUseCase.kt:121–131` (`isRetryable()`). Input is genuinely free-text (`response.errorMessage: String?`, `e.message`). `RotatingOpenRouterClient` surfaces raw Koog exceptions with no typed status codes (and commits the same sin: `isPaymentRequired()` does `contains("402")`).

**Recommended change.** Classify on typed exceptions / HTTP status codes surfaced by `RotatingOpenRouterClient` rather than substring matching; keep linear backoff or upgrade to exponential-with-jitter. **Note:** no typed errors exist today, so this requires introducing an exception hierarchy / parsing status out of the Koog client and rethreading it through `AiAnalysisPort` → `AiAnalysisResponse`.

**Risk / Effort.** Refactor risk: medium (multi-file, behavior-changing). Effort: small. Not auto-applicable.

### S8 — Idempotency window vs cache TTL mismatch

**Problem.** `signalKey` buckets by a fixed wall-clock floor window (`epochMillis / (windowMinutes*60_000)`), while the `TimeBasedCache` TTL is sliding-from-write. Two near-identical signals straddling a window boundary (e.g. t=1:59 and t=2:01) produce different keys, so the second is treated as new despite being seconds apart.

**Evidence.** `IdempotencyService.kt:116` (floor-bucket key); `TimeBasedCache.kt` confirmed `expireAfterWrite` (reads do not refresh), eviction bounded via `cleanupThreshold=1000` (the earlier "unbounded growth" claim is retracted). Default window=2 matches default ttl=2. Worst case: one extra near-duplicate order per boundary.

**Recommended change.** Align the dedup scheme: drop the floor-window from the key and rely solely on TTL, or hash adjacent windows.

**Risk / Effort.** Refactor risk: low. Effort: small. Not auto-applicable (floor-bucket vs sliding-TTL have no drop-in equivalence; a design choice that changes hit/miss behavior).

### S9 — Validation rejection reason → high-cardinality metric tag

**Problem.** The rejection reason concatenates per-symbol jakarta violation text (with symbols, coin names, raw numeric values) and is fed — `take(50)` + sanitized — into a metric tag, yielding high-cardinality, truncation-mangled tag values that are hard to aggregate.

**Evidence.** `AiSchemaValidator.kt:89` runs `validator.validate(args)` plus explicit range checks (106–136); reasons embed variable data (91–92). `UpdateCycleOrchestrator.kt:386–388` (`recordValidationRejection`) does `reason.take(50).replace(regex,"_").lowercase()` as the counter's `reason` tag. (Corrects an earlier finding that claimed validation was a bare `readValue` — it is not.)

**Recommended change.** Map failures to a small fixed set of stable rejection codes (`bean_validation`, `coin_mismatch`, `confidence_range`, `leverage_range`, `price_range`, ...) for the tag; keep detailed text in logs only.

**Risk / Effort.** Refactor risk: low. Effort: small. Not auto-applicable (taxonomy + type change shifts existing dashboards/alerts).

---

## 5. Developer Experience

### D1 — Risk/kill-switch pure policy lives in `application/risk`, not `domain`

**Problem.** `RiskContext`, `RiskDecision`, `KillSwitchSnapshot`, and the `RiskGate` port are framework-free pure policy, yet sit in `application/risk` while sibling pure policies (`OrderSizingPolicy`, `ApiKeyRotationPolicy`, `PromptBuilder`) live in `domain/services`. A new contributor cannot tell from the package which rules are the domain's invariants.

**Evidence.** `RiskGate.kt` is a 12-line pure interface; `RiskContext.kt`/`RiskDecision.kt` are framework-free; `KillSwitchState.kt` is a plain `AtomicReference` state machine. Sibling pure policies confirmed in `domain/services`. **Nuance:** the ordered-check *logic* lives in `RiskGateImpl` which IS `@Component`/Spring-managed (so it would stay in application); `RiskThresholds` is also `@Component`.

**Recommended change.** Move the pure port + data types (`RiskGate`, `RiskContext`, `RiskDecision`, `KillSwitchSnapshot`) to `domain/services` or a new `domain/risk` package; keep `RiskGateImpl` and wiring in application. Decide one consistent home for pure policy.

**Risk / Effort.** Refactor risk: medium (split port from impl; rewrites package declarations + imports/FQNs; interacts with component scanning). Effort: medium. Not auto-applicable.

### D2 — Stubbed adapters make behaviors intended-but-unimplemented

**Problem.** `getTodaysRealizedPnlUsd` returns `ZERO` (daily-loss cap inert) and Redis is a no-op default, so "distributed cache" and distributed idempotency/kill-switch are absent; multi-instance deploys share no state.

**Evidence.** `OkxExchangeAdapter.kt:157–162` (PnL stub, deferred to X2.a). `RedisCacheAdapter.kt`: `NoOpRedisCacheAdapter` is the default (`matchIfMissing=true`); `SpringRedisCacheAdapter` logs "not implemented" and returns `null`/`false`. (`OkxBaseWebSocketClient` is, by contrast, fully implemented — reconnect/backoff/jitter/heartbeat — correcting an earlier "empty stub" assumption.)

**Recommended change.** Track the PnL stub (X2.a) as a prerequisite before trusting the daily-loss cap; decide explicitly whether multi-instance is in scope (if so, kill-switch + idempotency need a shared store, not the no-op Redis).

**Risk / Effort.** Refactor risk: low. Effort: medium. Not auto-applicable (tracking/scope decision + external implementation).

### D3 — Dead `getKillSwitchState()` getter on the orchestrator ✅ (the only auto-apply item)

**Problem.** `UpdateCycleOrchestrator` exposes `fun getKillSwitchState(): KillSwitchState = killSwitchState` with **zero callers**; `RiskController` injects the bean directly via Spring DI. Dead code that needlessly widens the orchestrator's public surface.

**Evidence.** `UpdateCycleOrchestrator.kt:344` defines the getter; codegraph callers = 0; full-text grep finds the name only at its definition. `RiskController` injects `KillSwitchState` directly (constructor, ≈ line 10) and never touches the orchestrator. The backing `killSwitchState` field is still used by the orchestrator's risk logic, so only the getter is removed. (`KillSwitchState` is a `domain.risk` type, not infra — so the "leaks infra" framing is cosmetic; the dead-code fact stands.)

**Recommended change.** Delete the single line `fun getKillSwitchState(): KillSwitchState = killSwitchState`.

**Risk / Effort.** Refactor risk: low. Effort: small. **Auto-applicable** — mechanical, no callers, cannot change runtime behavior or break compilation.

---

## 6. Phased Plan

### Phase A — Low-risk refactors (auto-applied in this run)

Per the verifier's `safeToAutoApply` flags, **exactly one** finding qualifies as a mechanical, behavior-preserving edit:

- [ ] **D3 — Delete dead `getKillSwitchState()`** from `UpdateCycleOrchestrator.kt:344`. Zero callers; field retained for internal use. Verify with `gradlew.bat compileKotlin` after.

> The remaining low-severity items (S6, S7, S8, S9, D1, D2) are low *risk* but are **not** auto-applicable — each was explicitly marked `safeToAutoApply: false` because it requires a design decision (demo semantics, an exception taxonomy, a dedup scheme, a rejection-code taxonomy, a package-home decision, scope decisions). They belong in Phase B despite their low severity. Do not pad Phase A with them.

### Phase B — Requires human decision (medium/high risk)

Suggested sequencing respects real dependencies (shared files and prerequisite work):

**B0 — Foundational prerequisite (unblocks the risk story)**
1. **X2.a — Implement real `getTodaysRealizedPnlUsd`** (`/account/bills`). Until this lands, the daily-loss cap is inert and fixing S1's fail-open behavior is only half-meaningful. This is the gate for trusting the daily-loss control.

**B1 — Risk-gate correctness (batch these; they touch the same `stageExecute` / `ExecuteAiDecisionsUseCase` files)**
2. **P2 — Dedupe market load → single threaded snapshot.** Do this first within the batch: it changes the same signatures (`ExecuteAiDecisionsUseCase.execute`, `buildRiskContext`) that S1 and S3 also touch, so threading the snapshot once avoids editing these files three times. Resolve the freshness-vs-consistency tradeoff explicitly.
3. **S1 — Fail-CLOSED risk reads** (or gate auto-exec off on read failure). Builds on the threaded snapshot from P2; needs sign-off because it reverses documented X2 fail-open design.
4. **S3 — In-cycle placed-count / serialized gate+placement** to enforce `maxConcurrentPositions` per-order. Make check-then-place atomic to avoid TOCTOU.

**B2 — Execution & scheduling robustness**
5. **S4 — Typed OKX outcomes** (replace `null`-swallowing). Underpins safe retry/idempotency for the order path; sequence before tightening retry logic.
6. **P1 — Move the cycle off the scheduler thread** onto a dedicated dispatcher with a single-flight guard.
7. **S5 — Commit `lastPromptHash` only on full-cycle success** (resolve the manual-mode dedup question).
8. **S2 — Durable `KillSwitchStore`** (decide backend + multi-instance scope; coordinate with the D2 multi-instance decision).

**B3 — Lower-severity hardening / hygiene (low risk, still need a decision)**
9. **S7** typed retry classification (pairs naturally with S4's typed errors).
10. **S6** demo-mode side-effect isolation.
11. **S8** idempotency window/TTL alignment.
12. **S9** stable validation rejection codes.
13. **D1** move pure risk policy into `domain` (split port from `RiskGateImpl`).
14. **D2** multi-instance scope decision (couples with S2).

**Verification for every Phase B item:** add/extend a focused test, run `gradlew.bat test`, and confirm the specific behavior (fail-closed path, per-order cap, dedup-on-failure, etc.) before merge. Keep authoring and review in separate passes.

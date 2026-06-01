# X2 — Risk Management Hard Gates

**Status:** Approved, ready for implementation plan
**Date:** 2026-05-27
**Roadmap item:** Next-bucket X2 (`docs/ROADMAP.md`)
**Owner agent:** executor

## Problem

`ExecuteAiDecisionsUseCase` already runs a per-signal `ActionGuard` (leverage range, TP/SL distance/direction, quantity positivity). There is no **portfolio-wide** gate. Today, if the AI emits a streak of bad signals at the maximum 40× leverage with `auto-execute=true`, nothing stops the bot from blowing the account beyond per-order checks. The account has no realized-PnL cap, no concurrent-position cap, and no manual pause.

## Goals

1. Manual kill-switch via HTTP endpoint — operator can halt new order placement without restart.
2. Auto-trip kill-switch when today's realized PnL ≤ −`maxDailyLossUsd`.
3. Max concurrent open positions cap.
4. Hard-kill semantics: skip new orders, leave existing positions to OKX-side TP/SL brackets.

## Non-Goals (Deferred)

- Gross-notional USD cap across all positions
- Per-symbol notional cap
- Soft kill (allow exits / reductions while blocking new entries)
- Emergency flat-all on trip
- Persisted PnL ledger (waits on X1 trade journal)

## Architecture

```
ExecuteAiDecisionsUseCase.executePlan
   │
   ▼
ActionGuard.validate          (per-signal — exists today)
   │
   ▼
RiskGate.evaluate(context)    (NEW — portfolio-wide)
   │
   ▼
placeMarketOrderWithTpSl      (skipped if Block)
```

`RiskContext` is constructed **once per orchestrator cycle** by `UpdateCycleOrchestrator` and threaded into `ExecuteAiDecisionsUseCase.execute(...)`, so the PnL/positions reads are amortised across all symbols in that cycle rather than re-issued per order.

## Components

### 1. `RiskGate` — pure domain service

Location: `application/risk/RiskGate.kt`

```kotlin
class RiskGate(private val props: RiskGateProperties) {
    fun evaluate(ctx: RiskContext): RiskDecision { ... }
}

data class RiskContext(
    val openPositionsCount: Int,
    val todaysRealizedPnlUsd: BigDecimal,   // negative = loss; ZERO on PnL-read failure (fail-open)
    val killSwitch: KillSwitchSnapshot,
)

sealed class RiskDecision {
    data object Allow : RiskDecision()
    data class Block(val reason: String, val source: Source) : RiskDecision()
    enum class Source { MANUAL_KILL, DAILY_LOSS_CAP, POSITION_COUNT_CAP }
}
```

Evaluation order (short-circuit):
1. `killSwitch.enabled` → `Block(killSwitch.reason, MANUAL_KILL or DAILY_LOSS_CAP)` depending on the snapshot's `source` field.
2. `todaysRealizedPnlUsd ≤ -props.maxDailyLossUsd` → trip kill-switch (`source=AUTO_DAILY_LOSS`), then `Block(...)`.
3. `openPositionsCount ≥ props.maxConcurrentPositions` → `Block(...)`.
4. Otherwise `Allow`.

### 2. `KillSwitchState` — thread-safe in-process bean

Location: `application/risk/KillSwitchState.kt`

```kotlin
@Component
class KillSwitchState(private val clock: Clock) {
    private val ref = AtomicReference(KillSwitchSnapshot.disabled())

    fun snapshot(): KillSwitchSnapshot = ref.get().also { maybeRolloverAutoSource(it) }
    fun trip(reason: String, source: Source): KillSwitchSnapshot { ... }
    fun clear(): KillSwitchSnapshot { ... }
}

data class KillSwitchSnapshot(
    val enabled: Boolean,
    val reason: String?,
    val since: Instant?,
    val source: Source?,
) {
    enum class Source { MANUAL, AUTO_DAILY_LOSS }
    companion object { fun disabled() = KillSwitchSnapshot(false, null, null, null) }
}
```

**UTC midnight rollover:** when `snapshot()` is called and the current snapshot has `source == AUTO_DAILY_LOSS` and `since` is from a prior UTC day, the snapshot is auto-cleared. Manual trips never auto-clear.

### 3. `RiskGateProperties` — config binding

Location: `config/RiskGateProperties.kt`, bound under `trading.risk.*`:

```yaml
trading:
  risk:
    enabled: ${TRADING_RISK_ENABLED:true}
    max-daily-loss-usd: 50
    max-concurrent-positions: 5
```

When `enabled=false`, `RiskGate.evaluate` returns `Allow` unconditionally **and skips the auto-trip side effect** (escape hatch for ops — must not silently flip the kill state while gates are disabled). The kill-switch HTTP endpoint still functions; a manual kill is still respected because the orchestrator should always check `killSwitchState.snapshot().enabled` itself when wiring `RiskContext`, even when `trading.risk.enabled=false`. Document this explicitly in the wiring code comment.

### 4. PnL source — extend trading/account port

New method on the existing account-facing port (likely `TradingPort` since it already exposes `loadInstrument`, `setLeverage`):

```kotlin
suspend fun getTodaysRealizedPnlUsd(now: Instant): Either<Error, BigDecimal>
```

OKX adapter implementation: call `/account/bills` (or `/account/bills-archive` if today crosses an archive boundary), filter by `ts >= startOfUtcDay(now)`, sum the `pnl` field. Returns ZERO if no bills today.

**Failure mode:** on `Either.Left`, the orchestrator passes `BigDecimal.ZERO` into `RiskContext.todaysRealizedPnlUsd` and logs WARN. Rationale: a transient OKX bills outage must not freeze trading — the position-count cap and manual kill still protect. This is documented fail-open behaviour, not an oversight.

### 5. Orchestrator wiring

`UpdateCycleOrchestrator` builds `RiskContext` once per cycle:

```kotlin
val riskContext = RiskContext(
    openPositionsCount = state.positions.size,
    todaysRealizedPnlUsd = trading.getTodaysRealizedPnlUsd(clock.instant())
        .getOrElse { BigDecimal.ZERO.also { log.warn(it) {"PnL read failed, fail-open"} } },
    killSwitch = killSwitchState.snapshot(),
)
executeAiDecisionsUseCase.execute(decisions, riskContext)
```

`ExecuteAiDecisionsUseCase.execute` signature gains a `riskContext: RiskContext` parameter. Inside `executePlan`, after `actionGuard.validate` succeeds and before `placeMarketOrderWithTpSl`, call:

```kotlin
when (val d = riskGate.evaluate(riskContext)) {
    is RiskDecision.Allow -> { /* proceed */ }
    is RiskDecision.Block -> {
        recordRiskBlock(d.source.name)
        BusinessEventLogger.orderRejected(plan.symbol, null, d.reason, "RISK_${d.source.name}")
        return createSkippedResult(plan, "RiskGate(${d.source}): ${d.reason}")
    }
}
```

**Within-cycle re-evaluation:** `riskContext` is captured once per cycle and reused across all plans, so:

- If the loss cap trips on plan #1, plans #2…N within the same cycle re-evaluate against the same captured `todaysRealizedPnlUsd` and independently hit the same `DAILY_LOSS_CAP` Block — the kill-switch side effect from plan #1 doesn't change their outcome, it persists the trip into the **next** cycle.
- Position-count cap likewise blocks all plans uniformly because orders placed earlier in the cycle don't appear in `state.positions` until the next cycle's market-state load.
- Manual kill flipped via the HTTP endpoint mid-cycle is not seen until the next cycle's `RiskContext` build — acceptable given cycle duration is seconds.

### 6. HTTP endpoint

Added to `TradingSystemController` (or a new sibling `RiskController`):

- `POST /api/trading/kill-switch` — body `{enabled: Boolean, reason: String}` → `200 KillSwitchSnapshot`
  - `enabled=true` → calls `killSwitchState.trip(reason, MANUAL)`
  - `enabled=false` → calls `killSwitchState.clear()`
- `GET /api/trading/kill-switch` → `200 KillSwitchSnapshot`

No auth wired in this spec (matches existing controller). Tracked under L6 (secrets/auth) in the roadmap.

### 7. Metrics

- `risk.gate.blocked{source}` — counter, incremented on every `Block`
- `risk.killswitch.tripped{source}` — counter, incremented inside `trip()` (manual + auto)
- `risk.daily.pnl.usd` — gauge, polled via `Gauge.builder` reading the latest cached value from the orchestrator cycle
- `risk.open.positions` — gauge, same mechanism

## Testing

| Test class | Coverage |
|---|---|
| `RiskGateTest` | Allow path; each Block branch in isolation; precedence (kill before loss before count); `enabled=false` returns Allow even when conditions fail |
| `KillSwitchStateTest` | Initial disabled; trip + snapshot atomicity; clear; UTC midnight rollover for AUTO source only; manual trip survives rollover |
| `KillSwitchControllerTest` | MockMvc POST sets state; GET returns current state; idempotent re-trip |
| `OkxAccountAdapterPnlTest` | WireMock for `/account/bills` — empty response, multi-bill summation, archive-boundary fallback, malformed response → Left |

No integration test of the full orchestrator+gate pipeline in this spec — existing test coverage of the use-case is thin; adding one would balloon scope. Tracked separately.

## Risk Register

| Risk | Mitigation |
|---|---|
| `/account/bills` returning stale or paginated data understates today's loss | Sum across pages until first ts < startOfUtcDay; cap pagination at 10 pages with WARN if hit |
| Fail-open PnL read masks a real bills outage | WARN log + metric `risk.pnl.read.failures` so it's visible on the dashboard |
| AUTO_DAILY_LOSS auto-clears at midnight UTC even if the loss is not actually recovered | By design — the cap is *daily*. Operator can re-trip manually if needed |
| Kill-switch endpoint unauthenticated | Documented; deferred to L6 (secrets management). Network-level access control is the interim safeguard |

## Out of Scope (Reaffirmed)

Gross-notional cap, per-symbol cap, soft kill, flat-all, persisted PnL ledger. All belong to future roadmap items.

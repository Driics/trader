# X2 — Risk Management Hard Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add portfolio-wide risk gates (manual kill-switch + auto-trip on daily loss + max concurrent positions cap) that block new order placement before `placeMarketOrderWithTpSl`.

**Architecture:** A new `RiskGate` domain service is consulted inside `ExecuteAiDecisionsUseCase.executePlan` after the existing per-signal `ActionGuard`. `RiskContext` (positions, today's PnL, kill snapshot) is built once per cycle by `UpdateCycleOrchestrator` and threaded down. A `KillSwitchState` Spring bean holds the mutable kill flag, mutated via auto-trip or a new `POST /api/trading/kill-switch` endpoint.

**Tech Stack:** Kotlin 2.2, Spring Boot 3.5, Spring MVC, coroutines, Micrometer, MockK 1.14, WireMock 3.0, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-05-27-x2-risk-management-gates-design.md`

---

## File Map

**Create:**
- `src/main/kotlin/ru/driics/aitrade/config/RiskGateProperties.kt` — `@ConfigurationProperties("trading.risk")`
- `src/main/kotlin/ru/driics/aitrade/application/risk/KillSwitchSnapshot.kt` — immutable snapshot data class
- `src/main/kotlin/ru/driics/aitrade/application/risk/KillSwitchState.kt` — `@Component` holding `AtomicReference<KillSwitchSnapshot>`
- `src/main/kotlin/ru/driics/aitrade/application/risk/RiskContext.kt` — input data classes (`RiskContext`, `RiskDecision`)
- `src/main/kotlin/ru/driics/aitrade/application/risk/RiskGate.kt` — pure evaluation service
- `src/main/kotlin/ru/driics/aitrade/controller/RiskController.kt` — REST endpoints
- `src/test/kotlin/ru/driics/aitrade/application/risk/KillSwitchStateTest.kt`
- `src/test/kotlin/ru/driics/aitrade/application/risk/RiskGateTest.kt`
- `src/test/kotlin/ru/driics/aitrade/controller/RiskControllerTest.kt`

**Modify:**
- `src/main/kotlin/ru/driics/aitrade/domain/ports/TradingPort.kt` — add `getTodaysRealizedPnlUsd`
- `src/main/kotlin/ru/driics/aitrade/infra/exchange/OkxExchangeAdapter.kt` — implement it
- `src/main/kotlin/ru/driics/aitrade/application/usecase/ExecuteAiDecisionsUseCase.kt` — accept `RiskContext`, call `RiskGate`
- `src/main/kotlin/ru/driics/aitrade/application/orchestrator/UpdateCycleOrchestrator.kt` — build `RiskContext` once per cycle
- `src/main/kotlin/ru/driics/aitrade/config/ApplicationWiring.kt` — wire `RiskGate` bean + new deps
- `src/main/resources/application.yml` — add `trading.risk.*` block

---

## Task 1: Add `RiskGateProperties` config binding

**Files:**
- Create: `src/main/kotlin/ru/driics/aitrade/config/RiskGateProperties.kt`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Create the properties class**

Create `src/main/kotlin/ru/driics/aitrade/config/RiskGateProperties.kt`:

```kotlin
package ru.driics.aitrade.config

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@ConfigurationProperties(prefix = "trading.risk")
data class RiskGateProperties(
    var enabled: Boolean = true,

    @field:DecimalMin(value = "0.0", message = "Max daily loss must be non-negative")
    var maxDailyLossUsd: BigDecimal = BigDecimal("50"),

    @field:Min(value = 1, message = "Max concurrent positions must be at least 1")
    var maxConcurrentPositions: Int = 5,
)
```

- [ ] **Step 2: Add YAML block**

Edit `src/main/resources/application.yml` — insert this block after the existing `trading:` section (after the `demo-mode:` line, before the standalone `cache:` block at column 0):

```yaml
  # Portfolio-wide risk gates (X2)
  risk:
    enabled: ${TRADING_RISK_ENABLED:true}
    max-daily-loss-usd: 50
    max-concurrent-positions: 5
```

The two-space indentation is required so `risk:` is a child of `trading:`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/config/RiskGateProperties.kt src/main/resources/application.yml
git commit -m "feat(risk): add RiskGateProperties config binding"
```

---

## Task 2: Add `KillSwitchSnapshot` immutable type

**Files:**
- Create: `src/main/kotlin/ru/driics/aitrade/application/risk/KillSwitchSnapshot.kt`

- [ ] **Step 1: Create the snapshot type**

```kotlin
package ru.driics.aitrade.application.risk

import java.time.Instant

data class KillSwitchSnapshot(
    val enabled: Boolean,
    val reason: String?,
    val since: Instant?,
    val source: Source?,
) {
    enum class Source { MANUAL, AUTO_DAILY_LOSS }

    companion object {
        fun disabled(): KillSwitchSnapshot =
            KillSwitchSnapshot(enabled = false, reason = null, since = null, source = null)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/application/risk/KillSwitchSnapshot.kt
git commit -m "feat(risk): add KillSwitchSnapshot type"
```

---

## Task 3: Add `KillSwitchState` bean (TDD)

**Files:**
- Create: `src/test/kotlin/ru/driics/aitrade/application/risk/KillSwitchStateTest.kt`
- Create: `src/main/kotlin/ru/driics/aitrade/application/risk/KillSwitchState.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/ru/driics/aitrade/application/risk/KillSwitchStateTest.kt`:

```kotlin
package ru.driics.aitrade.application.risk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KillSwitchStateTest {

    private fun fixedClock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)

    @Test
    fun `initial snapshot is disabled`() {
        val state = KillSwitchState(fixedClock(Instant.parse("2026-05-27T12:00:00Z")))
        val snap = state.snapshot()
        assertFalse(snap.enabled)
        assertNull(snap.reason)
        assertNull(snap.since)
        assertNull(snap.source)
    }

    @Test
    fun `trip sets enabled, reason, since, source`() {
        val now = Instant.parse("2026-05-27T12:00:00Z")
        val state = KillSwitchState(fixedClock(now))
        val snap = state.trip("operator paused", KillSwitchSnapshot.Source.MANUAL)
        assertTrue(snap.enabled)
        assertEquals("operator paused", snap.reason)
        assertEquals(now, snap.since)
        assertEquals(KillSwitchSnapshot.Source.MANUAL, snap.source)
    }

    @Test
    fun `clear resets to disabled`() {
        val state = KillSwitchState(fixedClock(Instant.parse("2026-05-27T12:00:00Z")))
        state.trip("x", KillSwitchSnapshot.Source.MANUAL)
        val snap = state.clear()
        assertFalse(snap.enabled)
        assertNull(snap.source)
    }

    @Test
    fun `AUTO_DAILY_LOSS auto-clears at next UTC day`() {
        // Use a mutable clock surrogate: two states reading the same wall-clock supplier
        val tripTime = Instant.parse("2026-05-27T23:50:00Z")
        val nextDay = Instant.parse("2026-05-28T00:05:00Z")

        val state = KillSwitchState(fixedClock(tripTime))
        state.trip("daily loss", KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
        assertTrue(state.snapshot().enabled, "still tripped before midnight")

        // Re-wire with later clock and verify rollover clears
        val rolled = KillSwitchState(fixedClock(nextDay))
        // simulate persisted snapshot by tripping then advancing
        rolled.trip("daily loss", KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
        // Force the internal `since` to be yesterday by re-tripping with a clock at tripTime is awkward;
        // instead test via the dedicated helper exposed for testing:
        rolled.overrideForTest(
            KillSwitchSnapshot(
                enabled = true,
                reason = "daily loss",
                since = tripTime,
                source = KillSwitchSnapshot.Source.AUTO_DAILY_LOSS,
            )
        )
        val rolledSnap = rolled.snapshot()
        assertFalse(rolledSnap.enabled, "AUTO source must auto-clear on next UTC day")
    }

    @Test
    fun `MANUAL trip survives UTC day rollover`() {
        val tripTime = Instant.parse("2026-05-27T23:50:00Z")
        val nextDay = Instant.parse("2026-05-28T00:05:00Z")

        val state = KillSwitchState(fixedClock(nextDay))
        state.overrideForTest(
            KillSwitchSnapshot(
                enabled = true,
                reason = "operator",
                since = tripTime,
                source = KillSwitchSnapshot.Source.MANUAL,
            )
        )
        assertTrue(state.snapshot().enabled, "MANUAL trip must NOT auto-clear")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradlew.bat test --tests "ru.driics.aitrade.application.risk.KillSwitchStateTest" --console=plain`
Expected: FAIL (KillSwitchState class doesn't exist yet).

- [ ] **Step 3: Implement KillSwitchState**

Create `src/main/kotlin/ru/driics/aitrade/application/risk/KillSwitchState.kt`:

```kotlin
package ru.driics.aitrade.application.risk

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the kill-switch flag.
 *
 * AUTO_DAILY_LOSS trips auto-clear at UTC midnight rollover. MANUAL trips persist
 * until explicitly cleared via [clear]. See the X2 design spec for rationale.
 */
@Component
class KillSwitchState(private val clock: Clock) {

    private val ref = AtomicReference(KillSwitchSnapshot.disabled())

    /** Returns the current snapshot, applying UTC-midnight rollover for AUTO sources. */
    fun snapshot(): KillSwitchSnapshot {
        val current = ref.get()
        if (current.shouldAutoClear(clock)) {
            val cleared = KillSwitchSnapshot.disabled()
            // Best-effort CAS; if a concurrent trip wins, return that new state instead.
            return if (ref.compareAndSet(current, cleared)) cleared else ref.get()
        }
        return current
    }

    fun trip(reason: String, source: KillSwitchSnapshot.Source): KillSwitchSnapshot {
        val next = KillSwitchSnapshot(
            enabled = true,
            reason = reason,
            since = clock.instant(),
            source = source,
        )
        ref.set(next)
        return next
    }

    fun clear(): KillSwitchSnapshot {
        val next = KillSwitchSnapshot.disabled()
        ref.set(next)
        return next
    }

    /** Test-only seam to set internal state without relying on a mutable clock. */
    internal fun overrideForTest(snapshot: KillSwitchSnapshot) {
        ref.set(snapshot)
    }

    private fun KillSwitchSnapshot.shouldAutoClear(clock: Clock): Boolean {
        if (!enabled || source != KillSwitchSnapshot.Source.AUTO_DAILY_LOSS) return false
        val since = this.since ?: return false
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val sinceDay = LocalDate.ofInstant(since, ZoneOffset.UTC)
        return today.isAfter(sinceDay)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat test --tests "ru.driics.aitrade.application.risk.KillSwitchStateTest" --console=plain`
Expected: PASS for all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/application/risk/KillSwitchState.kt src/test/kotlin/ru/driics/aitrade/application/risk/KillSwitchStateTest.kt
git commit -m "feat(risk): add KillSwitchState bean with UTC-day rollover"
```

---

## Task 4: Add `RiskContext` and `RiskDecision` types

**Files:**
- Create: `src/main/kotlin/ru/driics/aitrade/application/risk/RiskContext.kt`

- [ ] **Step 1: Create the types**

```kotlin
package ru.driics.aitrade.application.risk

import java.math.BigDecimal

/**
 * Snapshot of portfolio state evaluated once per orchestrator cycle and threaded
 * into [ExecuteAiDecisionsUseCase]. PnL of ZERO is used when the OKX read fails
 * (fail-open behaviour — see X2 design spec).
 */
data class RiskContext(
    val openPositionsCount: Int,
    val todaysRealizedPnlUsd: BigDecimal,
    val killSwitch: KillSwitchSnapshot,
)

sealed class RiskDecision {
    data object Allow : RiskDecision()
    data class Block(val reason: String, val source: Source) : RiskDecision()

    enum class Source { MANUAL_KILL, DAILY_LOSS_CAP, POSITION_COUNT_CAP }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/application/risk/RiskContext.kt
git commit -m "feat(risk): add RiskContext and RiskDecision types"
```

---

## Task 5: Add `RiskGate` service (TDD)

**Files:**
- Create: `src/test/kotlin/ru/driics/aitrade/application/risk/RiskGateTest.kt`
- Create: `src/main/kotlin/ru/driics/aitrade/application/risk/RiskGate.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package ru.driics.aitrade.application.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.RiskGateProperties
import java.math.BigDecimal
import java.time.Instant

class RiskGateTest {

    private val props = RiskGateProperties(
        enabled = true,
        maxDailyLossUsd = BigDecimal("50"),
        maxConcurrentPositions = 5,
    )

    private val killState = mockk<KillSwitchState>(relaxed = true)
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry =
        io.micrometer.core.instrument.simple.SimpleMeterRegistry()

    private fun ctx(
        openPositions: Int = 0,
        pnl: BigDecimal = BigDecimal.ZERO,
        kill: KillSwitchSnapshot = KillSwitchSnapshot.disabled(),
    ) = RiskContext(openPositions, pnl, kill)

    @Test
    fun `clean state returns Allow`() {
        val gate = RiskGate(props, killState, meterRegistry)
        assertEquals(RiskDecision.Allow, gate.evaluate(ctx()))
    }

    @Test
    fun `manual kill blocks with MANUAL_KILL source`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val manual = KillSwitchSnapshot(true, "ops", Instant.EPOCH, KillSwitchSnapshot.Source.MANUAL)
        val decision = gate.evaluate(ctx(kill = manual))
        assertTrue(decision is RiskDecision.Block)
        assertEquals(RiskDecision.Source.MANUAL_KILL, (decision as RiskDecision.Block).source)
        assertTrue(decision.reason.contains("ops"))
    }

    @Test
    fun `auto-tripped kill blocks with DAILY_LOSS_CAP source`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val auto = KillSwitchSnapshot(true, "loss", Instant.EPOCH, KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
        val decision = gate.evaluate(ctx(kill = auto))
        assertEquals(RiskDecision.Source.DAILY_LOSS_CAP, (decision as RiskDecision.Block).source)
    }

    @Test
    fun `pnl at minus cap trips kill-switch and blocks with DAILY_LOSS_CAP`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(pnl = BigDecimal("-50")))
        assertTrue(decision is RiskDecision.Block)
        assertEquals(RiskDecision.Source.DAILY_LOSS_CAP, (decision as RiskDecision.Block).source)
        verify { killState.trip(any(), KillSwitchSnapshot.Source.AUTO_DAILY_LOSS) }
        // Counter increment for observability
        assertEquals(
            1.0,
            meterRegistry.find("risk.killswitch.tripped").tag("source", "AUTO_DAILY_LOSS").counter()?.count(),
        )
    }

    @Test
    fun `pnl below minus cap also trips`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(pnl = BigDecimal("-100")))
        assertEquals(RiskDecision.Source.DAILY_LOSS_CAP, (decision as RiskDecision.Block).source)
    }

    @Test
    fun `pnl above minus cap does not trip`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(pnl = BigDecimal("-49.99")))
        assertEquals(RiskDecision.Allow, decision)
        verify(exactly = 0) { killState.trip(any(), any()) }
    }

    @Test
    fun `open positions at cap blocks with POSITION_COUNT_CAP`() {
        val gate = RiskGate(props, killState, meterRegistry)
        val decision = gate.evaluate(ctx(openPositions = 5))
        assertEquals(RiskDecision.Source.POSITION_COUNT_CAP, (decision as RiskDecision.Block).source)
    }

    @Test
    fun `open positions below cap does not block`() {
        val gate = RiskGate(props, killState, meterRegistry)
        assertEquals(RiskDecision.Allow, gate.evaluate(ctx(openPositions = 4)))
    }

    @Test
    fun `disabled gate always returns Allow even with breached caps`() {
        val gate = RiskGate(props.copy(enabled = false), killState, meterRegistry)
        val decision = gate.evaluate(ctx(openPositions = 10, pnl = BigDecimal("-1000")))
        assertEquals(RiskDecision.Allow, decision)
        verify(exactly = 0) { killState.trip(any(), any()) }
    }

    @Test
    fun `kill snapshot precedence over loss cap and position cap`() {
        // All three conditions breached; ensure MANUAL_KILL wins
        val gate = RiskGate(props, killState, meterRegistry)
        val manual = KillSwitchSnapshot(true, "ops", Instant.EPOCH, KillSwitchSnapshot.Source.MANUAL)
        val decision = gate.evaluate(ctx(openPositions = 99, pnl = BigDecimal("-9999"), kill = manual))
        assertEquals(RiskDecision.Source.MANUAL_KILL, (decision as RiskDecision.Block).source)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradlew.bat test --tests "ru.driics.aitrade.application.risk.RiskGateTest" --console=plain`
Expected: FAIL (RiskGate class doesn't exist).

- [ ] **Step 3: Implement RiskGate**

```kotlin
package ru.driics.aitrade.application.risk

import ru.driics.aitrade.config.RiskGateProperties

/**
 * Portfolio-wide risk evaluation. Stateless except for the side effect of
 * tripping [KillSwitchState] when the daily-loss cap is breached.
 *
 * Evaluation order: kill-switch -> daily loss -> position count.
 * If `props.enabled` is false, always returns [RiskDecision.Allow] and skips
 * the auto-trip side effect.
 */
class RiskGate(
    private val props: RiskGateProperties,
    private val killSwitch: KillSwitchState,
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry,
) {
    fun evaluate(ctx: RiskContext): RiskDecision {
        if (!props.enabled) return RiskDecision.Allow

        // 1. Kill-switch (manual or previously auto-tripped) wins over everything.
        if (ctx.killSwitch.enabled) {
            val source = when (ctx.killSwitch.source) {
                KillSwitchSnapshot.Source.MANUAL -> RiskDecision.Source.MANUAL_KILL
                KillSwitchSnapshot.Source.AUTO_DAILY_LOSS -> RiskDecision.Source.DAILY_LOSS_CAP
                null -> RiskDecision.Source.MANUAL_KILL
            }
            return RiskDecision.Block(
                reason = ctx.killSwitch.reason ?: "kill-switch enabled",
                source = source,
            )
        }

        // 2. Daily loss cap. Negative PnL = loss; compare against -maxDailyLossUsd.
        val lossThreshold = props.maxDailyLossUsd.negate()
        if (ctx.todaysRealizedPnlUsd <= lossThreshold) {
            val reason = "daily realized PnL ${ctx.todaysRealizedPnlUsd} <= cap $lossThreshold"
            killSwitch.trip(reason, KillSwitchSnapshot.Source.AUTO_DAILY_LOSS)
            meterRegistry.counter("risk.killswitch.tripped", "source", "AUTO_DAILY_LOSS").increment()
            return RiskDecision.Block(reason = reason, source = RiskDecision.Source.DAILY_LOSS_CAP)
        }

        // 3. Concurrent position cap.
        if (ctx.openPositionsCount >= props.maxConcurrentPositions) {
            return RiskDecision.Block(
                reason = "open positions ${ctx.openPositionsCount} >= cap ${props.maxConcurrentPositions}",
                source = RiskDecision.Source.POSITION_COUNT_CAP,
            )
        }

        return RiskDecision.Allow
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat test --tests "ru.driics.aitrade.application.risk.RiskGateTest" --console=plain`
Expected: PASS for all 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/application/risk/RiskGate.kt src/test/kotlin/ru/driics/aitrade/application/risk/RiskGateTest.kt
git commit -m "feat(risk): add RiskGate service with kill/loss/position gates"
```

---

## Task 6: Extend `TradingPort` with `getTodaysRealizedPnlUsd`

**Files:**
- Modify: `src/main/kotlin/ru/driics/aitrade/domain/ports/TradingPort.kt`
- Modify: `src/main/kotlin/ru/driics/aitrade/infra/exchange/OkxExchangeAdapter.kt`

- [ ] **Step 1: Add the port method**

Edit `src/main/kotlin/ru/driics/aitrade/domain/ports/TradingPort.kt` — append this method to the `TradingPort` interface (after `placeMarketOrderWithTpSl`, before the closing brace):

```kotlin
    /**
     * Returns the sum of realized PnL (USD-equivalent) for fills closed since
     * the start of the current UTC day. Returns ZERO when there are no closing
     * fills today. Implementations must not throw on transient exchange errors —
     * surface them via TradeResult.failure so callers can fail-open.
     */
    suspend fun getTodaysRealizedPnlUsd(now: java.time.Instant): TradeResult<java.math.BigDecimal>
```

- [ ] **Step 2: Implement on the OKX adapter (stub returning ZERO)**

In `src/main/kotlin/ru/driics/aitrade/infra/exchange/OkxExchangeAdapter.kt`, locate the line with `override suspend fun setLeverage(` (around line 149) and add this method AFTER the existing `setLeverage` implementation (preserving the existing closing brace of the class):

```kotlin
    override suspend fun getTodaysRealizedPnlUsd(now: java.time.Instant): TradeResult<java.math.BigDecimal> {
        // Stub returning ZERO until the /account/bills integration lands.
        // The risk gate treats ZERO as "no loss today" which fails open — see X2 design spec.
        // Tracked as a follow-up: wire OKX bills client + WireMock test.
        return TradeResult.success(java.math.BigDecimal.ZERO)
    }
```

If `TradeResult.success(...)` isn't the right factory name in this codebase, check the existing call sites at `OkxExchangeAdapter.kt:81` (`loadInstrument`) — use whatever success-factory it uses (e.g., `TradeResult.success(...)` vs constructor invocation).

- [ ] **Step 3: Run the build to verify the interface compiles**

Run: `gradlew.bat compileKotlin compileTestKotlin --console=plain`
Expected: BUILD SUCCESSFUL. If MockK-based mocks of `TradingPort` exist in other tests, they need to be `relaxed = true` or stub the new method. Search and patch:

Run: `grep -rn "mockk<TradingPort>" src/test/kotlin/` — for each match, add `relaxed = true` if not already present.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/domain/ports/TradingPort.kt src/main/kotlin/ru/driics/aitrade/infra/exchange/OkxExchangeAdapter.kt
git commit -m "feat(risk): add TradingPort.getTodaysRealizedPnlUsd (stub)"
```

> **Note:** Real `/account/bills` integration is intentionally deferred. The stub returns ZERO so the gate fails open, which matches the documented behaviour in the spec. Tracked as a follow-up issue (add to `docs/ROADMAP.md` under Next as "X2.a: wire OKX bills for daily-PnL gate").

---

## Task 7: Thread `RiskContext` through `ExecuteAiDecisionsUseCase`

**Files:**
- Modify: `src/main/kotlin/ru/driics/aitrade/application/usecase/ExecuteAiDecisionsUseCase.kt`

- [ ] **Step 1: Add constructor dependency on RiskGate**

In `ExecuteAiDecisionsUseCase.kt`, modify the constructor (around lines 29-36) to add a `riskGate: RiskGate` parameter:

```kotlin
class ExecuteAiDecisionsUseCase(
    private val trading: TradingPort,
    private val market: MarketDataPort,
    private val tradingProperties: TradingProperties,
    private val clock: Clock,
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val meterRegistry: MeterRegistry,
    private val riskGate: ru.driics.aitrade.application.risk.RiskGate,
)
```

- [ ] **Step 2: Update `execute` signature to accept RiskContext**

Change `suspend fun execute(decisions: AiTradeDecisionMap): List<AiTradeExecutionResult>` (line 61) to:

```kotlin
suspend fun execute(
    decisions: AiTradeDecisionMap,
    riskContext: ru.driics.aitrade.application.risk.RiskContext,
): List<AiTradeExecutionResult> = coroutineScope {
```

- [ ] **Step 3: Thread `riskContext` into `executePlan`**

Inside `execute`, find the `.flatMapMerge` block (around line 99). Change the inner call from:

```kotlin
executePlan(ready.plan, state.account.availableCash)
```

to:

```kotlin
executePlan(ready.plan, state.account.availableCash, riskContext)
```

- [ ] **Step 4: Update `executePlan` signature and add the gate call**

Find `private suspend fun executePlan(plan: OrderPlan, availableUsd: BigDecimal)` (around line 196). Change to:

```kotlin
private suspend fun executePlan(
    plan: OrderPlan,
    availableUsd: BigDecimal,
    riskContext: ru.driics.aitrade.application.risk.RiskContext,
): AiTradeExecutionResult {
    // Risk-gate check before sizing. Skipped on Block, not throwing.
    when (val d = riskGate.evaluate(riskContext)) {
        is ru.driics.aitrade.application.risk.RiskDecision.Allow -> Unit
        is ru.driics.aitrade.application.risk.RiskDecision.Block -> {
            recordRiskBlock(d.source.name)
            ru.driics.aitrade.common.logging.BusinessEventLogger.orderRejected(
                plan.symbol, null, d.reason, "RISK_${d.source.name}",
            )
            return createSkippedResult(plan, "RiskGate(${d.source}): ${d.reason}")
        }
    }

    // 1. Sizing  <-- existing code from here unchanged
    val sizing = sizingPolicy.size(
```

Keep the rest of the original `executePlan` body intact starting from `val sizing = sizingPolicy.size(...)`.

- [ ] **Step 5: Add the `recordRiskBlock` helper**

Add this helper near `recordGuardRejection` (around line 358):

```kotlin
    private fun recordRiskBlock(source: String) {
        meterRegistry.counter("risk.gate.blocked", "source", source).increment()
    }
```

- [ ] **Step 6: Verify it compiles**

Run: `gradlew.bat compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/application/usecase/ExecuteAiDecisionsUseCase.kt
git commit -m "feat(risk): wire RiskGate into ExecuteAiDecisionsUseCase"
```

---

## Task 8: Build `RiskContext` in `UpdateCycleOrchestrator`

**Files:**
- Modify: `src/main/kotlin/ru/driics/aitrade/application/orchestrator/UpdateCycleOrchestrator.kt`

- [ ] **Step 1: Find the call site of `execute.execute(decisions)`**

Run: `grep -n "execute.execute\|execute(decisions" src/main/kotlin/ru/driics/aitrade/application/orchestrator/UpdateCycleOrchestrator.kt`

Identify the line that calls `execute.execute(decisions)` (the ExecuteAiDecisionsUseCase bean is referenced as `execute` in the orchestrator).

- [ ] **Step 2: Add KillSwitchState dependency**

Add `private val killSwitchState: ru.driics.aitrade.application.risk.KillSwitchState,` to the `UpdateCycleOrchestrator` primary constructor's parameter list (right before the closing `)`).

- [ ] **Step 3: Build the RiskContext before the execute call**

Immediately before the line that calls `execute.execute(decisions)`, insert:

```kotlin
            // Build RiskContext once per cycle. PnL read failure -> fail-open (ZERO).
            val pnl = trading.getTodaysRealizedPnlUsd(clock.instant())
                .fold(
                    onSuccess = { it ?: java.math.BigDecimal.ZERO },
                    onFailure = {
                        log.warn(it) { "Failed to read today's realized PnL; defaulting to ZERO (fail-open)" }
                        java.math.BigDecimal.ZERO
                    },
                )
            // Manual kill is always honoured even when trading.risk.enabled=false.
            val killSnap = killSwitchState.snapshot()
            val riskContext = ru.driics.aitrade.application.risk.RiskContext(
                openPositionsCount = state.positions.size,
                todaysRealizedPnlUsd = pnl,
                killSwitch = killSnap,
            )
```

Then change `execute.execute(decisions)` to `execute.execute(decisions, riskContext)`.

> **If `trading` (TradingPort) is not already injected into the orchestrator:** Check the constructor signature. If absent, add `private val trading: ru.driics.aitrade.domain.ports.TradingPort,` to the constructor and update `ApplicationWiring.updateCycleOrchestrator` to pass it.
>
> **If `state` is not the variable name** holding the per-cycle `MarketState`, use whatever local name the orchestrator uses. Search the file for `.positions` to locate it.
>
> **If `.fold(onSuccess=…, onFailure=…)` is not the API exposed by `TradeResult`**, check `domain/types/TradeResult.kt` for the actual API surface (it may be `getOrNull()` / `exceptionOrNull()` pattern). Adapt accordingly.

- [ ] **Step 4: Verify it compiles**

Run: `gradlew.bat compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/application/orchestrator/UpdateCycleOrchestrator.kt
git commit -m "feat(risk): build RiskContext per cycle in orchestrator"
```

---

## Task 9: Wire `RiskGate` bean in `ApplicationWiring`

**Files:**
- Modify: `src/main/kotlin/ru/driics/aitrade/config/ApplicationWiring.kt`

- [ ] **Step 1: Add the bean and update existing factories**

Edit `ApplicationWiring.kt`. Add this method anywhere inside the class:

```kotlin
    @Bean
    fun riskGate(
        riskGateProperties: RiskGateProperties,
        killSwitchState: ru.driics.aitrade.application.risk.KillSwitchState,
        meterRegistry: MeterRegistry,
    ) = ru.driics.aitrade.application.risk.RiskGate(
        props = riskGateProperties,
        killSwitch = killSwitchState,
        meterRegistry = meterRegistry,
    )
```

- [ ] **Step 2: Update `executeAiUseCase` to inject RiskGate**

Change the existing `executeAiUseCase` bean (lines 87-100) to:

```kotlin
    @Bean
    fun executeAiUseCase(
        trading: TradingPort,
        market: MarketDataPort,
        clock: Clock,
        confidenceCalibrator: ConfidenceCalibrator,
        meterRegistry: MeterRegistry,
        riskGate: ru.driics.aitrade.application.risk.RiskGate,
    ) = ExecuteAiDecisionsUseCase(
        trading = trading,
        market = market,
        tradingProperties = tradingProperties,
        clock = clock,
        confidenceCalibrator = confidenceCalibrator,
        meterRegistry = meterRegistry,
        riskGate = riskGate,
    )
```

- [ ] **Step 3: Update `updateCycleOrchestrator` to inject KillSwitchState (and TradingPort if not already)**

Modify the existing `updateCycleOrchestrator` bean (lines 109-134) to add `killSwitchState` (and `trading: TradingPort` if it wasn't already a parameter — Task 8 noted this). The body invocation must pass them into the constructor.

- [ ] **Step 4: Run full build**

Run: `gradlew.bat build -x test --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/config/ApplicationWiring.kt
git commit -m "feat(risk): wire RiskGate bean and dependencies"
```

---

## Task 10: Add `RiskController` REST endpoints (TDD)

**Files:**
- Create: `src/test/kotlin/ru/driics/aitrade/controller/RiskControllerTest.kt`
- Create: `src/main/kotlin/ru/driics/aitrade/controller/RiskController.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.driics.aitrade.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.driics.aitrade.application.risk.KillSwitchSnapshot
import ru.driics.aitrade.application.risk.KillSwitchState
import java.time.Instant

class RiskControllerTest {

    private val killState: KillSwitchState = mockk(relaxed = true)
    private val controller = RiskController(killState)
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    private val mapper = ObjectMapper()

    @Test
    fun `GET returns current snapshot`() {
        every { killState.snapshot() } returns KillSwitchSnapshot.disabled()

        mvc.perform(get("/api/trading/kill-switch"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
    }

    @Test
    fun `POST enabled=true calls trip with MANUAL source`() {
        val tripped = KillSwitchSnapshot(true, "paused", Instant.EPOCH, KillSwitchSnapshot.Source.MANUAL)
        every { killState.trip(any(), KillSwitchSnapshot.Source.MANUAL) } returns tripped

        mvc.perform(
            post("/api/trading/kill-switch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true,"reason":"paused"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.reason").value("paused"))
            .andExpect(jsonPath("$.source").value("MANUAL"))

        verify { killState.trip("paused", KillSwitchSnapshot.Source.MANUAL) }
    }

    @Test
    fun `POST enabled=false calls clear`() {
        every { killState.clear() } returns KillSwitchSnapshot.disabled()

        mvc.perform(
            post("/api/trading/kill-switch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":false,"reason":"resume"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))

        verify { killState.clear() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat test --tests "ru.driics.aitrade.controller.RiskControllerTest" --console=plain`
Expected: FAIL (RiskController doesn't exist).

- [ ] **Step 3: Implement the controller**

```kotlin
package ru.driics.aitrade.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.driics.aitrade.application.risk.KillSwitchSnapshot
import ru.driics.aitrade.application.risk.KillSwitchState

@RestController
@RequestMapping("/api/trading/kill-switch")
class RiskController(
    private val killSwitchState: KillSwitchState,
) {
    data class KillSwitchRequest(val enabled: Boolean, val reason: String)

    @GetMapping
    fun get(): ResponseEntity<KillSwitchSnapshot> =
        ResponseEntity.ok(killSwitchState.snapshot())

    @PostMapping
    fun set(@RequestBody body: KillSwitchRequest): ResponseEntity<KillSwitchSnapshot> {
        val next = if (body.enabled) {
            killSwitchState.trip(body.reason, KillSwitchSnapshot.Source.MANUAL)
        } else {
            killSwitchState.clear()
        }
        return ResponseEntity.ok(next)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat test --tests "ru.driics.aitrade.controller.RiskControllerTest" --console=plain`
Expected: PASS for all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/driics/aitrade/controller/RiskController.kt src/test/kotlin/ru/driics/aitrade/controller/RiskControllerTest.kt
git commit -m "feat(risk): add kill-switch REST endpoints"
```

---

## Task 11: Full-suite verification

**Files:** none

- [ ] **Step 1: Run the entire test suite**

Run: `gradlew.bat test --console=plain`
Expected: BUILD SUCCESSFUL. All tests pass — particularly verify the existing `IndicatorCalculatorTest`, `OrderSizingPolicyTest`, and `AiTraderApplicationTests` (Spring context loads with the new beans).

If `AiTraderApplicationTests.contextLoads` fails, the most likely cause is a missing bean injection in `ApplicationWiring.kt` from Task 9. Re-read the error stack and fix the wiring.

- [ ] **Step 2: Confirm metrics surface via actuator**

Run the app locally with `gradlew.bat bootRun` and `curl http://localhost:8080/actuator/prometheus | grep -E "risk_(gate|killswitch)"`. The counters won't show until an order is processed but the meter names should be reachable once the bean graph wires.

(Skip this step if running in CI without Docker.)

- [ ] **Step 3: Commit no-op marker if you made any tweaks during verification**

If the verification surfaced no changes, no commit. Otherwise:

```bash
git add -A
git commit -m "chore(risk): post-verification fixes"
```

---

## Task 12: Update ROADMAP.md

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: Move X2 from Next to Now-completed and add the follow-up**

Edit `docs/ROADMAP.md`:
- Strike or remove the X2 row from the **Next — 2–4 weeks** table.
- In the **Now — this week** section, add a row noting X2 landed.
- Under **Next**, add a new row: `X2.a | Wire OKX /account/bills for real daily-PnL gate (currently stubbed to ZERO) | M | backend-architect`.

- [ ] **Step 2: Commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): mark X2 landed, track X2.a follow-up"
```

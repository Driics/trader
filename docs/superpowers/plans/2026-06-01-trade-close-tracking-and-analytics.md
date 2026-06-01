# Trade-Close Tracking + Journal Analytics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture per-trade closes (realized PnL) by polling OKX `positions-history` each cycle, and expose realized trading performance (win-rate, avg-R, profit-factor, equity curve, drawdown, per-symbol) over a read-only JSON API.

**Architecture:** Hexagonal, mirroring the existing trade journal. Write side extends `TradeJournalPort` with `recordClose`; a per-cycle fail-safe `TradeCloseCollector` polls a new `ClosedPositionsPort` and journals new closes (idempotent via UNIQUE `pos_id`). Read side adds a separate `TradeJournalQueryPort`; a pure `PerformanceAnalytics` domain service computes all metrics; `AnalyticsController` serves them. Everything is gated by `trade-journal.enabled` (default off → NoOp).

**Tech Stack:** Kotlin 2.2, Spring Boot 3.5, `NamedParameterJdbcTemplate` (Postgres prod / H2 in tests), Liquibase, Ktor OKX client, JUnit5 + kotlin-test + MockK + H2.

**Spec:** `docs/superpowers/specs/2026-06-01-trade-close-tracking-and-analytics-api-design.md`

---

## File Structure

**Create:**
- `src/main/kotlin/ru/driics/aitrade/domain/journal/JournaledClose.kt` — write DTO for a closed trade.
- `src/main/kotlin/ru/driics/aitrade/domain/model/ClosedPosition.kt` — pure domain view of an OKX closed position.
- `src/main/kotlin/ru/driics/aitrade/domain/ports/ClosedPositionsPort.kt` — outbound port to fetch closed positions.
- `src/main/kotlin/ru/driics/aitrade/domain/ports/TradeJournalQueryPort.kt` — read port + row/filter DTOs.
- `src/main/kotlin/ru/driics/aitrade/domain/analytics/PerformanceModels.kt` — result models (`PerformanceSummary`, `SymbolPerformance`, `EquityPoint`).
- `src/main/kotlin/ru/driics/aitrade/domain/analytics/PerformanceAnalytics.kt` — pure metric service.
- `src/main/kotlin/ru/driics/aitrade/infra/exchange/OkxClosedPositionsAdapter.kt` — `ClosedPositionsPort` impl over `OkxAccountClient`.
- `src/main/kotlin/ru/driics/aitrade/application/journal/TradeCloseCollector.kt` — per-cycle capture.
- `src/main/kotlin/ru/driics/aitrade/infra/persistence/JdbcTradeJournalQuery.kt` + `NoOpTradeJournalQuery.kt`.
- `src/main/kotlin/ru/driics/aitrade/controller/AnalyticsController.kt`.
- Tests mirroring each (see tasks).

**Modify:**
- `db.changelog-master.yaml` — new `trade_close` changeset.
- `domain/ports/TradeJournalPort.kt`, `infra/persistence/JdbcTradeJournal.kt`, `NoOpTradeJournal.kt` — `recordClose`.
- `application/usecase/ExecuteAiDecisionsUseCase.kt` — journal `risk_usd` (currently `null`).
- `application/orchestrator/UpdateCycleOrchestrator.kt` — invoke the collector per cycle.
- `config/TradeJournalPersistenceConfig.kt` + `config/ApplicationWiring.kt` — wire new beans.

> **Convention reminder:** every `record*`/capture path is **fail-safe** — log at WARN, swallow, never rethrow (see `JdbcTradeJournal`). Reads may surface errors via `GlobalExceptionHandler`.

---

## Task 1: Journal `risk_usd` on entries (unblocks avg-R)

`ExecuteAiDecisionsUseCase.handleSuccessfulOrder` writes `riskUsd = null`; the AI's intended risk is on `args`. Thread it through so R is computable.

**Files:**
- Modify: `src/main/kotlin/ru/driics/aitrade/application/usecase/ExecuteAiDecisionsUseCase.kt`
- Test: `src/test/kotlin/ru/driics/aitrade/application/usecase/ExecuteAiDecisionsUseCaseTest.kt`

- [ ] **Step 1: Read the current code.** Open `ExecuteAiDecisionsUseCase.kt`. Confirm `handleSuccessfulOrder(plan, sizing, clOrdId, ordId, demo)` builds `JournaledOrder(..., riskUsd = null, ...)`, and that `buildPlan`/`executePlan` have access to the originating `AiTradeSignalArgs.riskUsd`. The plan currently does **not** carry `riskUsd` onto `OrderPlan`.

- [ ] **Step 2: Write the failing test.** Add to `ExecuteAiDecisionsUseCaseTest.kt` a test asserting the journaled order carries the AI's `risk_usd`. Capture the `JournaledOrder` via a MockK slot on `tradeJournal.recordOrder`.

```kotlin
@Test
fun `journals the AI intended risk_usd on a placed order`() = runTest {
    val captured = slot<JournaledOrder>()
    every { tradeJournal.recordOrder(capture(captured)) } just Runs
    // ... existing fixture that drives one BUY decision with risk_usd = 25.0 to a PLACED result ...

    useCase.execute(decisionsWith(riskUsd = BigDecimal("25.0")), riskContext, marketState)

    assertEquals(0, BigDecimal("25.0").compareTo(captured.captured.riskUsd))
}
```

> Use the test's existing fixture/helpers for building decisions, `riskContext`, `marketState`, and the `useCase`. If no helper exists, mirror the setup of the nearest existing "places an order" test in this file.

- [ ] **Step 3: Run it; verify it fails.** Run: `./gradlew.bat test --tests "*ExecuteAiDecisionsUseCaseTest*"`. Expected: FAIL — `captured.riskUsd` is `null`, `compareTo` throws NPE or assertion fails.

- [ ] **Step 4: Carry `riskUsd` to the plan and journal it.** In `OrderPlan`, add `val riskUsd: BigDecimal?`. In `buildPlan`, set `riskUsd = args.riskUsd` when constructing `OrderPlan(...)`. In `handleSuccessfulOrder`, change `riskUsd = null` to `riskUsd = plan.riskUsd`.

- [ ] **Step 5: Run tests; verify pass.** Run: `./gradlew.bat test --tests "*ExecuteAiDecisionsUseCaseTest*"`. Expected: PASS.

- [ ] **Step 6: Commit.**
```bash
git add src/main/kotlin/ru/driics/aitrade/application/usecase/ExecuteAiDecisionsUseCase.kt src/test/kotlin/ru/driics/aitrade/application/usecase/ExecuteAiDecisionsUseCaseTest.kt
git commit -m "feat(journal): record the AI's intended risk_usd on placed orders (unblocks avg-R)"
```

---

## Task 2: `trade_close` table + `JournaledClose` + `recordClose`

**Files:**
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `src/main/kotlin/ru/driics/aitrade/domain/journal/JournaledClose.kt`
- Modify: `src/main/kotlin/ru/driics/aitrade/domain/ports/TradeJournalPort.kt`, `infra/persistence/JdbcTradeJournal.kt`, `infra/persistence/NoOpTradeJournal.kt`
- Test: `src/test/kotlin/ru/driics/aitrade/infra/persistence/JdbcTradeJournalTest.kt`

- [ ] **Step 1: Add the `JournaledClose` record.** Create `JournaledClose.kt`:
```kotlin
package ru.driics.aitrade.domain.journal

import ru.driics.aitrade.domain.model.TradingMode
import java.math.BigDecimal

/** A closed position captured from OKX positions-history. `posId` is unique per close. */
data class JournaledClose(
    val recordedAtMs: Long,
    val posId: String,
    val instId: String,
    val symbol: String,
    val side: String?,            // "long" / "short" (OKX `direction`), nullable if absent
    val realizedPnl: BigDecimal,  // OKX realizedPnl = pnl + fee + fundingFee + liqPenalty
    val openTimeMs: Long,
    val closeTimeMs: Long,
    val mode: TradingMode,
    val demo: Boolean,
)
```

- [ ] **Step 2: Add the Liquibase changeset.** Append a new `changeSet` (id `v2-trade-close-table`) to `db.changelog-master.yaml`, after the existing `v1-trade-journal-tables` changeSet, at the same indentation:
```yaml
  - changeSet:
      id: v2-trade-close-table
      author: aitrader
      changes:
        - createTable:
            tableName: trade_close
            columns:
              - column: { name: id, type: BIGINT, autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: recorded_at, type: TIMESTAMP }
              - column:
                  name: pos_id
                  type: VARCHAR(64)
                  constraints: { unique: true, uniqueConstraintName: uq_trade_close_pos_id }
              - column: { name: inst_id, type: VARCHAR(64) }
              - column: { name: symbol, type: VARCHAR(64) }
              - column: { name: side, type: VARCHAR(64) }
              - column: { name: realized_pnl, type: "NUMERIC(38, 18)" }
              - column: { name: open_time, type: TIMESTAMP }
              - column: { name: close_time, type: TIMESTAMP }
              - column: { name: mode, type: VARCHAR(64) }
              - column: { name: demo, type: BOOLEAN }
```

- [ ] **Step 3: Extend the write port.** In `TradeJournalPort.kt` add (and import `JournaledClose`):
```kotlin
    fun recordClose(close: JournaledClose)
```

- [ ] **Step 4: NoOp impl.** In `NoOpTradeJournal.kt` add:
```kotlin
    override fun recordClose(close: JournaledClose) = Unit
```

- [ ] **Step 5: Write the failing JDBC tests.** In `JdbcTradeJournalTest.kt` add two tests (mirroring the existing `recordOrder` tests):
```kotlin
@Test
fun `recordClose persists a readable row`() {
    val close = JournaledClose(
        recordedAtMs = 1_700_000_010_000L, posId = "pos-1", instId = "BTC-USDT-SWAP",
        symbol = "BTC", side = "long", realizedPnl = BigDecimal("12.34"),
        openTimeMs = 1_700_000_000_000L, closeTimeMs = 1_700_000_009_000L,
        mode = TradingMode.PAPER, demo = true,
    )
    journal.recordClose(close)
    val row = jdbc.queryForMap("SELECT * FROM trade_close WHERE pos_id = :p", MapSqlParameterSource("p", "pos-1"))
    assertEquals("BTC-USDT-SWAP", row["inst_id"])
    assertEquals("long", row["side"])
    assertEquals(0, BigDecimal("12.34").compareTo(row["realized_pnl"] as BigDecimal))
    assertEquals(true, row["demo"])
}

@Test
fun `recordClose is idempotent on pos_id (duplicate is swallowed)`() {
    val close = JournaledClose(
        recordedAtMs = 1L, posId = "dup", instId = "ETH-USDT-SWAP", symbol = "ETH", side = "short",
        realizedPnl = BigDecimal("-5"), openTimeMs = 1L, closeTimeMs = 2L, mode = TradingMode.PAPER, demo = false,
    )
    journal.recordClose(close)
    journal.recordClose(close) // must not throw despite the UNIQUE(pos_id) violation
    val count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM trade_close WHERE pos_id = :p", MapSqlParameterSource("p", "dup"), Int::class.java)
    assertEquals(1, count)
}
```
Add `import ru.driics.aitrade.domain.journal.JournaledClose`.

- [ ] **Step 6: Run; verify fail.** Run: `./gradlew.bat test --tests "*JdbcTradeJournalTest*"`. Expected: FAIL — `recordClose` unresolved / table missing.

- [ ] **Step 7: Implement `recordClose` in `JdbcTradeJournal`.** Mirror the existing fail-safe pattern:
```kotlin
override fun recordClose(close: JournaledClose) {
    try {
        val params = MapSqlParameterSource()
            .addValue("recorded_at", toTimestamp(close.recordedAtMs))
            .addValue("pos_id", close.posId)
            .addValue("inst_id", close.instId)
            .addValue("symbol", close.symbol)
            .addValue("side", close.side)
            .addValue("realized_pnl", close.realizedPnl)
            .addValue("open_time", toTimestamp(close.openTimeMs))
            .addValue("close_time", toTimestamp(close.closeTimeMs))
            .addValue("mode", close.mode.name)
            .addValue("demo", close.demo)
        jdbc.update(
            """
            INSERT INTO trade_close
                (recorded_at, pos_id, inst_id, symbol, side, realized_pnl, open_time, close_time, mode, demo)
            VALUES
                (:recorded_at, :pos_id, :inst_id, :symbol, :side, :realized_pnl, :open_time, :close_time, :mode, :demo)
            """.trimIndent(),
            params,
        )
    } catch (e: Exception) {
        // Idempotent + fail-safe: a UNIQUE(pos_id) violation means "already journaled"; any other error
        // (e.g. DB outage) must never break a cycle. Both are logged and swallowed.
        log.warn(e) { "Skipped journaling close posId=${close.posId} (duplicate or DB error; cycle continues)" }
    }
}
```
Add `import ru.driics.aitrade.domain.journal.JournaledClose`.

- [ ] **Step 8: Run; verify pass.** Run: `./gradlew.bat test --tests "*JdbcTradeJournalTest*"`. Expected: PASS (both new tests + existing).

- [ ] **Step 9: Commit.**
```bash
git add src/main/resources/db/changelog/db.changelog-master.yaml src/main/kotlin/ru/driics/aitrade/domain/journal/JournaledClose.kt src/main/kotlin/ru/driics/aitrade/domain/ports/TradeJournalPort.kt src/main/kotlin/ru/driics/aitrade/infra/persistence/JdbcTradeJournal.kt src/main/kotlin/ru/driics/aitrade/infra/persistence/NoOpTradeJournal.kt src/test/kotlin/ru/driics/aitrade/infra/persistence/JdbcTradeJournalTest.kt
git commit -m "feat(journal): trade_close table + idempotent recordClose"
```

---

## Task 3: `ClosedPositionsPort` + OKX adapter

Wrap the existing `OkxAccountClient.fetchPositionsHistory` behind a domain port that returns clean `ClosedPosition`s, paginating until older than a cutoff.

**Files:**
- Create: `domain/model/ClosedPosition.kt`, `domain/ports/ClosedPositionsPort.kt`, `infra/exchange/OkxClosedPositionsAdapter.kt`
- Test: `src/test/kotlin/ru/driics/aitrade/infra/exchange/OkxClosedPositionsAdapterTest.kt`

- [ ] **Step 1: Confirm/extend the OKX model.** Open `domain/model/OkxResponseModels.kt:160` (`OkxPositionHistoryData`). It has `posId`, `instId`, `realizedPnl`, `createdTime`(cTime), `updatedTime`(uTime). Confirm whether it also carries `direction` (long/short). If **not present**, add it (backward-compatible, defaulted):
```kotlin
    @JsonProperty("direction")
    val direction: String = "",
```

- [ ] **Step 2: Add the domain model.** Create `ClosedPosition.kt`:
```kotlin
package ru.driics.aitrade.domain.model

import java.math.BigDecimal

/** A position OKX has fully closed. `closeTimeMs` is the high-water-mark key for incremental capture. */
data class ClosedPosition(
    val posId: String,
    val instId: String,
    val side: String?,
    val realizedPnl: BigDecimal,
    val openTimeMs: Long,
    val closeTimeMs: Long,
)
```

- [ ] **Step 3: Add the port.** Create `ClosedPositionsPort.kt`:
```kotlin
package ru.driics.aitrade.domain.ports

import ru.driics.aitrade.domain.model.ClosedPosition

/** Outbound port: fetch positions closed strictly after [sinceMs] (newest-first source, returned as a list). */
interface ClosedPositionsPort {
    suspend fun closedSince(sinceMs: Long): List<ClosedPosition>
}
```

- [ ] **Step 4: Write the failing adapter test.** Create `OkxClosedPositionsAdapterTest.kt`. Stub `OkxAccountClient.fetchPositionsHistory` with MockK to return one page, assert mapping + the `sinceMs` filter:
```kotlin
package ru.driics.aitrade.infra.exchange

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.driics.aitrade.domain.model.OkxApiResponse
import ru.driics.aitrade.domain.model.OkxPositionHistoryData
import ru.driics.aitrade.service.okx.OkxAccountClient
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class OkxClosedPositionsAdapterTest {
    private val account = mockk<OkxAccountClient>()
    private val adapter = OkxClosedPositionsAdapter(account)

    @Test
    fun `maps history rows and drops those at or before sinceMs`() = runTest {
        coEvery { account.fetchPositionsHistory(any(), any()) } returns OkxApiResponse(
            code = "0", msg = "", data = listOf(
                OkxPositionHistoryData(instId = "BTC-USDT-SWAP", posId = "p2", realizedPnl = "10",
                    createdTime = "1000", updatedTime = "3000", direction = "long"),
                OkxPositionHistoryData(instId = "ETH-USDT-SWAP", posId = "p1", realizedPnl = "-4",
                    createdTime = "500", updatedTime = "2000", direction = "short"),
            )
        )
        val out = adapter.closedSince(sinceMs = 2000L)
        assertEquals(1, out.size)               // p1 closeTime=2000 is NOT strictly after 2000 → dropped
        assertEquals("p2", out[0].posId)
        assertEquals(0, BigDecimal("10").compareTo(out[0].realizedPnl))
        assertEquals(3000L, out[0].closeTimeMs)
    }

    @Test
    fun `returns empty when the read fails (null) — fail-safe`() = runTest {
        coEvery { account.fetchPositionsHistory(any(), any()) } returns null
        assertEquals(emptyList(), adapter.closedSince(0L))
    }
}
```
> Confirm the `OkxApiResponse` constructor parameter names (`code`, `msg`, `data`) against `domain/model/OkxResponseModels.kt`; adjust the literals if they differ.

- [ ] **Step 5: Run; verify fail.** Run: `./gradlew.bat test --tests "*OkxClosedPositionsAdapterTest*"`. Expected: FAIL — adapter does not exist.

- [ ] **Step 6: Implement the adapter.** Create `OkxClosedPositionsAdapter.kt`:
```kotlin
package ru.driics.aitrade.infra.exchange

import org.springframework.stereotype.Component
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.service.okx.OkxAccountClient
import java.math.BigDecimal

/**
 * Fetches closed positions via OKX positions-history and maps them to [ClosedPosition], keeping only
 * closes strictly newer than `sinceMs`. Paginates (by `posId`) until a page goes at/under the cutoff or
 * is empty, capped at [MAX_PAGES] so a misbehaving feed cannot loop. Read failures yield an empty list.
 */
@Component
class OkxClosedPositionsAdapter(
    private val account: OkxAccountClient,
) : ClosedPositionsPort {

    private companion object {
        val log = logger<OkxClosedPositionsAdapter>()
        const val MAX_PAGES = 10
        const val PAGE = 100
    }

    override suspend fun closedSince(sinceMs: Long): List<ClosedPosition> {
        val out = ArrayList<ClosedPosition>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val resp = account.fetchPositionsHistory(after = after, limit = PAGE) ?: return out
            if (!resp.isSuccess() || resp.data.isEmpty()) return out
            for (d in resp.data) {
                val closeMs = d.updatedTime.toLongOrNull() ?: 0L
                if (closeMs > sinceMs) {
                    out += ClosedPosition(
                        posId = d.posId,
                        instId = d.instId,
                        side = d.direction.ifBlank { null },
                        realizedPnl = d.realizedPnl.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                        openTimeMs = d.createdTime.toLongOrNull() ?: 0L,
                        closeTimeMs = closeMs,
                    )
                }
            }
            // History is newest-first; once a full page no longer beats the cutoff, older pages won't either.
            if (resp.data.all { (it.updatedTime.toLongOrNull() ?: 0L) <= sinceMs }) return out
            after = resp.data.last().posId
        }
        log.warn { "positions-history hit MAX_PAGES=$MAX_PAGES; returning ${out.size} closes" }
        return out
    }
}
```
> Confirm `OkxApiResponse.isSuccess()` and `.data` exist (they do — see `OkxAccountClient.fetchOpenPositions`). Confirm `OkxPositionHistoryData` field names `posId/instId/realizedPnl/createdTime/updatedTime/direction`.

- [ ] **Step 7: Run; verify pass.** Run: `./gradlew.bat test --tests "*OkxClosedPositionsAdapterTest*"`. Expected: PASS.

- [ ] **Step 8: Commit.**
```bash
git add src/main/kotlin/ru/driics/aitrade/domain/model/ClosedPosition.kt src/main/kotlin/ru/driics/aitrade/domain/model/OkxResponseModels.kt src/main/kotlin/ru/driics/aitrade/domain/ports/ClosedPositionsPort.kt src/main/kotlin/ru/driics/aitrade/infra/exchange/OkxClosedPositionsAdapter.kt src/test/kotlin/ru/driics/aitrade/infra/exchange/OkxClosedPositionsAdapterTest.kt
git commit -m "feat(exchange): ClosedPositionsPort over OKX positions-history"
```

---

## Task 4: `TradeJournalQueryPort` + JDBC read adapter + NoOp

Read side: raw rows + a `latestCloseTimeMs()` used by the collector to rehydrate its high-water mark.

**Files:**
- Create: `domain/ports/TradeJournalQueryPort.kt`, `infra/persistence/JdbcTradeJournalQuery.kt`, `infra/persistence/NoOpTradeJournalQuery.kt`
- Test: `src/test/kotlin/ru/driics/aitrade/infra/persistence/JdbcTradeJournalQueryTest.kt`

- [ ] **Step 1: Define the read port + DTOs.** Create `TradeJournalQueryPort.kt`:
```kotlin
package ru.driics.aitrade.domain.ports

import java.math.BigDecimal

/** Filters shared by all analytics reads. Null = unbounded/any. */
data class AnalyticsFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val mode: String? = null,
    val symbol: String? = null,
)

data class ClosedTradeRow(
    val posId: String, val instId: String, val symbol: String, val side: String?,
    val realizedPnl: BigDecimal, val openTimeMs: Long, val closeTimeMs: Long,
)

/** A PLACED entry's intended risk, for linking closes → risk (avg-R). */
data class EntryRiskRow(val instId: String, val recordedAtMs: Long, val riskUsd: BigDecimal?)

data class PnlSnapshotRow(val timestampMs: Long, val accountValue: BigDecimal)

/** Read side of the journal (separate from the write [TradeJournalPort]). */
interface TradeJournalQueryPort {
    fun latestCloseTimeMs(): Long?
    fun closedTrades(filter: AnalyticsFilter): List<ClosedTradeRow>
    fun entryRisks(filter: AnalyticsFilter): List<EntryRiskRow>
    fun pnlSnapshots(filter: AnalyticsFilter): List<PnlSnapshotRow>
}
```

- [ ] **Step 2: NoOp impl.** Create `NoOpTradeJournalQuery.kt`:
```kotlin
package ru.driics.aitrade.infra.persistence

import ru.driics.aitrade.domain.ports.*

/** Used when the journal is disabled: every read is empty. */
class NoOpTradeJournalQuery : TradeJournalQueryPort {
    override fun latestCloseTimeMs(): Long? = null
    override fun closedTrades(filter: AnalyticsFilter): List<ClosedTradeRow> = emptyList()
    override fun entryRisks(filter: AnalyticsFilter): List<EntryRiskRow> = emptyList()
    override fun pnlSnapshots(filter: AnalyticsFilter): List<PnlSnapshotRow> = emptyList()
}
```

- [ ] **Step 3: Write the failing JDBC read test.** Create `JdbcTradeJournalQueryTest.kt`, reusing the H2 + Liquibase scaffold from `JdbcTradeJournalTest` (copy `setUp`/`tearDown`/`runChangelog`; construct both `JdbcTradeJournal` to seed and `JdbcTradeJournalQuery` to read):
```kotlin
@Test
fun `closedTrades returns inserted closes filtered by symbol, and latestCloseTimeMs is the max`() {
    journal.recordClose(JournaledClose(1L, "p1", "BTC-USDT-SWAP", "BTC", "long",
        BigDecimal("10"), 1L, 1_000L, TradingMode.PAPER, true))
    journal.recordClose(JournaledClose(2L, "p2", "ETH-USDT-SWAP", "ETH", "short",
        BigDecimal("-4"), 1L, 2_000L, TradingMode.PAPER, true))

    val all = query.closedTrades(AnalyticsFilter())
    assertEquals(2, all.size)
    val btc = query.closedTrades(AnalyticsFilter(symbol = "BTC"))
    assertEquals(1, btc.size)
    assertEquals("p1", btc[0].posId)
    assertEquals(2_000L, query.latestCloseTimeMs())
}

@Test
fun `pnlSnapshots returns the equity series ordered by time`() {
    journal.recordPnlSnapshot(JournaledPnlSnapshot(100L, 1L, BigDecimal("10000"), BigDecimal("9000"),
        BigDecimal.ZERO, null, 0))
    journal.recordPnlSnapshot(JournaledPnlSnapshot(200L, 2L, BigDecimal("10100"), BigDecimal("9000"),
        BigDecimal.ZERO, null, 1))
    val series = query.pnlSnapshots(AnalyticsFilter())
    assertEquals(listOf(100L, 200L), series.map { it.timestampMs })
    assertEquals(0, BigDecimal("10100").compareTo(series[1].accountValue))
}
```
Wire `private lateinit var query: JdbcTradeJournalQuery` in `setUp`: `query = JdbcTradeJournalQuery(jdbc)`. Add needed imports.

- [ ] **Step 4: Run; verify fail.** Run: `./gradlew.bat test --tests "*JdbcTradeJournalQueryTest*"`. Expected: FAIL — class missing.

- [ ] **Step 5: Implement the JDBC read adapter.** Create `JdbcTradeJournalQuery.kt`:
```kotlin
package ru.driics.aitrade.infra.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.driics.aitrade.domain.ports.*
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * JDBC reads over the journal tables. Time filters compare `recorded_at`/`close_time` against epoch-ms
 * bounds; `mode`/`symbol` are equality filters. Returns raw rows — all metric math lives in
 * [ru.driics.aitrade.domain.analytics.PerformanceAnalytics].
 */
class JdbcTradeJournalQuery(
    private val jdbc: NamedParameterJdbcTemplate,
) : TradeJournalQueryPort {

    override fun latestCloseTimeMs(): Long? =
        jdbc.queryForObject(
            "SELECT MAX(close_time) FROM trade_close", MapSqlParameterSource(),
        ) { rs, _ -> rs.getTimestamp(1)?.time }

    override fun closedTrades(filter: AnalyticsFilter): List<ClosedTradeRow> {
        val (where, params) = whereFor(filter, timeCol = "close_time", symbolCol = "symbol", modeCol = "mode")
        return jdbc.query(
            "SELECT pos_id, inst_id, symbol, side, realized_pnl, open_time, close_time FROM trade_close $where ORDER BY close_time",
            params,
        ) { rs, _ ->
            ClosedTradeRow(
                posId = rs.getString("pos_id"), instId = rs.getString("inst_id"),
                symbol = rs.getString("symbol"), side = rs.getString("side"),
                realizedPnl = rs.getBigDecimal("realized_pnl") ?: BigDecimal.ZERO,
                openTimeMs = rs.ms("open_time"), closeTimeMs = rs.ms("close_time"),
            )
        }
    }

    override fun entryRisks(filter: AnalyticsFilter): List<EntryRiskRow> {
        // Only PLACED entries carry a usable risk; reuse symbol/mode/time filters on trade_order.
        val (where, params) = whereFor(filter, timeCol = "recorded_at", symbolCol = "symbol", modeCol = "mode")
        val clause = if (where.isEmpty()) "WHERE status = 'PLACED'" else "$where AND status = 'PLACED'"
        return jdbc.query(
            "SELECT inst_id, recorded_at, risk_usd FROM trade_order $clause ORDER BY recorded_at",
            params,
        ) { rs, _ -> EntryRiskRow(rs.getString("inst_id"), rs.ms("recorded_at"), rs.getBigDecimal("risk_usd")) }
    }

    override fun pnlSnapshots(filter: AnalyticsFilter): List<PnlSnapshotRow> {
        val (where, params) = whereFor(filter, timeCol = "recorded_at", symbolCol = null, modeCol = null)
        return jdbc.query(
            "SELECT recorded_at, account_value FROM pnl_snapshot $where ORDER BY recorded_at",
            params,
        ) { rs, _ -> PnlSnapshotRow(rs.ms("recorded_at"), rs.getBigDecimal("account_value") ?: BigDecimal.ZERO) }
    }

    private fun whereFor(f: AnalyticsFilter, timeCol: String, symbolCol: String?, modeCol: String?): Pair<String, MapSqlParameterSource> {
        val conds = ArrayList<String>()
        val p = MapSqlParameterSource()
        f.fromMs?.let { conds += "$timeCol >= :from"; p.addValue("from", Timestamp(it)) }
        f.toMs?.let { conds += "$timeCol <= :to"; p.addValue("to", Timestamp(it)) }
        if (symbolCol != null) f.symbol?.let { conds += "$symbolCol = :symbol"; p.addValue("symbol", it) }
        if (modeCol != null) f.mode?.let { conds += "$modeCol = :mode"; p.addValue("mode", it) }
        val where = if (conds.isEmpty()) "" else "WHERE " + conds.joinToString(" AND ")
        return where to p
    }

    private fun ResultSet.ms(col: String): Long = getTimestamp(col)?.time ?: 0L
}
```

- [ ] **Step 6: Run; verify pass.** Run: `./gradlew.bat test --tests "*JdbcTradeJournalQueryTest*"`. Expected: PASS.

- [ ] **Step 7: Commit.**
```bash
git add src/main/kotlin/ru/driics/aitrade/domain/ports/TradeJournalQueryPort.kt src/main/kotlin/ru/driics/aitrade/infra/persistence/JdbcTradeJournalQuery.kt src/main/kotlin/ru/driics/aitrade/infra/persistence/NoOpTradeJournalQuery.kt src/test/kotlin/ru/driics/aitrade/infra/persistence/JdbcTradeJournalQueryTest.kt
git commit -m "feat(journal): TradeJournalQueryPort + JDBC read adapter"
```

---

## Task 5: `TradeCloseCollector` + per-cycle wiring

Fail-safe per-cycle capture: rehydrate the high-water mark once, then each cycle fetch new closes and journal them.

**Files:**
- Create: `application/journal/TradeCloseCollector.kt`
- Modify: `application/orchestrator/UpdateCycleOrchestrator.kt`, `config/ApplicationWiring.kt`, `config/TradeJournalPersistenceConfig.kt`
- Test: `src/test/kotlin/ru/driics/aitrade/application/journal/TradeCloseCollectorTest.kt`

- [ ] **Step 1: Write the failing collector test.** Create `TradeCloseCollectorTest.kt`:
```kotlin
package ru.driics.aitrade.application.journal

import io.mockk.*
import kotlinx.coroutines.test.runTest
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeCloseCollectorTest {
    private val source = mockk<ClosedPositionsPort>()
    private val journal = mockk<TradeJournalPort>(relaxed = true)
    private val query = mockk<TradeJournalQueryPort>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(9_000L), ZoneOffset.UTC)
    private fun collector() = TradeCloseCollector(source, journal, query, clock, TradingMode.PAPER, demo = true)

    @Test
    fun `journals new closes and advances the high-water mark`() = runTest {
        every { query.latestCloseTimeMs() } returns 1_000L
        coEvery { source.closedSince(1_000L) } returns listOf(
            ClosedPosition("p2", "BTC-USDT-SWAP", "long", BigDecimal("10"), 1L, 2_000L))
        val c = collector()

        c.collect()

        val slot = slot<JournaledClose>()
        verify(exactly = 1) { journal.recordClose(capture(slot)) }
        assertEquals("p2", slot.captured.posId)
        assertEquals(2_000L, slot.captured.closeTimeMs)

        // Next cycle uses the advanced in-memory mark (2_000), not the DB value again.
        coEvery { source.closedSince(2_000L) } returns emptyList()
        c.collect()
        coVerify { source.closedSince(2_000L) }
    }

    @Test
    fun `never throws when the source blows up`() = runTest {
        every { query.latestCloseTimeMs() } returns null
        coEvery { source.closedSince(any()) } throws RuntimeException("boom")
        collector().collect() // must not throw
    }
}
```

- [ ] **Step 2: Run; verify fail.** Run: `./gradlew.bat test --tests "*TradeCloseCollectorTest*"`. Expected: FAIL — class missing.

- [ ] **Step 3: Implement the collector.** Create `TradeCloseCollector.kt`:
```kotlin
package ru.driics.aitrade.application.journal

import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.journal.JournaledClose
import ru.driics.aitrade.domain.model.ClosedPosition
import ru.driics.aitrade.domain.model.TradingMode
import ru.driics.aitrade.domain.ports.ClosedPositionsPort
import ru.driics.aitrade.domain.ports.TradeJournalPort
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-cycle trade-close capture (fail-safe). Rehydrates a high-water mark from the journal on first use,
 * then each [collect] fetches closes newer than the mark and journals them. Dedup is belt-and-suspenders:
 * the in-memory mark bounds the fetch window, and `recordClose` is idempotent on `pos_id`, so a restart
 * (mark reseeded from `MAX(close_time)`) can neither double-count nor gap. Any error is swallowed.
 */
class TradeCloseCollector(
    private val source: ClosedPositionsPort,
    private val journal: TradeJournalPort,
    private val query: TradeJournalQueryPort,
    private val clock: Clock,
    private val mode: TradingMode,
    private val demo: Boolean,
) {
    private companion object { val log = logger<TradeCloseCollector>() }

    private val highWater = AtomicLong(Long.MIN_VALUE) // MIN = not yet rehydrated

    suspend fun collect() {
        try {
            if (highWater.get() == Long.MIN_VALUE) {
                highWater.set(query.latestCloseTimeMs() ?: 0L)
            }
            val since = highWater.get()
            val closes = source.closedSince(since)
            if (closes.isEmpty()) return
            val now = clock.instant().toEpochMilli()
            var maxClose = since
            for (cp in closes) {
                journal.recordClose(cp.toJournaled(now))
                if (cp.closeTimeMs > maxClose) maxClose = cp.closeTimeMs
            }
            highWater.set(maxClose)
            log.info { "Captured ${closes.size} trade close(s); high-water=$maxClose" }
        } catch (e: Exception) {
            log.warn(e) { "Trade-close capture failed (cycle continues)" }
        }
    }

    private fun ClosedPosition.toJournaled(nowMs: Long) = JournaledClose(
        recordedAtMs = nowMs, posId = posId, instId = instId,
        symbol = instId.substringBefore("-"), side = side, realizedPnl = realizedPnl,
        openTimeMs = openTimeMs, closeTimeMs = closeTimeMs, mode = mode, demo = demo,
    )
}
```

- [ ] **Step 4: Run; verify pass.** Run: `./gradlew.bat test --tests "*TradeCloseCollectorTest*"`. Expected: PASS.

- [ ] **Step 5: Wire the beans.** In `TradeJournalPersistenceConfig.kt` (the class that conditionally wires `JdbcTradeJournal` vs `NoOpTradeJournal` on `trade-journal.enabled`), add — following the **same** `@ConditionalOnProperty(name=["trade-journal.enabled"], havingValue="true")` / `matchIfMissing` pattern already used there:
```kotlin
// enabled path:
@Bean fun tradeJournalQuery(jdbc: NamedParameterJdbcTemplate): TradeJournalQueryPort = JdbcTradeJournalQuery(jdbc)
@Bean fun tradeCloseCollector(
    source: ClosedPositionsPort, journal: TradeJournalPort, query: TradeJournalQueryPort,
    clock: Clock, tradingMode: TradingMode, tradingProperties: TradingProperties,
): TradeCloseCollector = TradeCloseCollector(source, journal, query, clock, tradingMode, tradingProperties.demoMode)

// disabled path (mirror the existing NoOp wiring):
@Bean fun tradeJournalQuery(): TradeJournalQueryPort = NoOpTradeJournalQuery()
@Bean fun tradeCloseCollector(): TradeCloseCollector? = null  // no collection when journal is off
```
> Match the existing file's structure (two nested `@Configuration @ConditionalOnProperty` classes, or two methods with conditions). Confirm how `TradingMode`/`Clock`/`TradingProperties` beans are obtained there (they are already injected for `JdbcTradeJournal`/`ExecuteAiDecisionsUseCase`). If the disabled side cannot supply a `null` bean cleanly, make the collector a no-op by passing a `NoOpTradeJournalQuery` + `ClosedPositionsPort` that returns empty — but the simplest correct wiring is: only call the collector when non-null (next step).

- [ ] **Step 6: Invoke per cycle (fail-safe, optional collaborator).** In `UpdateCycleOrchestrator`, add a nullable collaborator `tradeCloseCollector` to the same infrastructure holder that already carries `tradeJournal` (default `null`), and call it once per cycle in `stageExecute`, right after the existing `recordPnlSnapshot(...)`:
```kotlin
infrastructure.tradeCloseCollector?.let { collector ->
    runCatching { collector.collect() }
        .onFailure { log.warn(it) { "trade-close collect failed (cycle continues)" } }
}
```
> `collect()` is already internally fail-safe; the `runCatching` is defense-in-depth. Wire the new field through `ApplicationWiring` where the orchestrator's infrastructure is constructed, defaulting to `null` when the `tradeCloseCollector` bean is absent (journal disabled). Follow exactly how `tradeJournal` is threaded today.

- [ ] **Step 7: Full build + tests.** Run: `./gradlew.bat test`. Expected: BUILD SUCCESSFUL (the app context still wires; collector is null when journal disabled, so default test profile is unaffected).

- [ ] **Step 8: Commit.**
```bash
git add src/main/kotlin/ru/driics/aitrade/application/journal/TradeCloseCollector.kt src/main/kotlin/ru/driics/aitrade/application/orchestrator/UpdateCycleOrchestrator.kt src/main/kotlin/ru/driics/aitrade/config/TradeJournalPersistenceConfig.kt src/main/kotlin/ru/driics/aitrade/config/ApplicationWiring.kt src/test/kotlin/ru/driics/aitrade/application/journal/TradeCloseCollectorTest.kt
git commit -m "feat(journal): per-cycle fail-safe TradeCloseCollector"
```

---

## Task 6: `PerformanceAnalytics` pure service + result models

The metric math. Pure functions, no DB — the bulk of the unit tests.

**Files:**
- Create: `domain/analytics/PerformanceModels.kt`, `domain/analytics/PerformanceAnalytics.kt`
- Test: `src/test/kotlin/ru/driics/aitrade/domain/analytics/PerformanceAnalyticsTest.kt`

- [ ] **Step 1: Define result models.** Create `PerformanceModels.kt`:
```kotlin
package ru.driics.aitrade.domain.analytics

import java.math.BigDecimal

data class PerformanceSummary(
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val scratches: Int,
    val winRate: BigDecimal,           // 0..1, scale 4; 0 when no decisive trades
    val grossProfit: BigDecimal,
    val grossLoss: BigDecimal,          // positive magnitude
    val netRealizedPnl: BigDecimal,
    val profitFactor: BigDecimal?,      // null when grossLoss == 0
    val avgWin: BigDecimal?,            // null when wins == 0
    val avgLoss: BigDecimal?,           // null when losses == 0
    val expectancyUsd: BigDecimal?,     // net / totalTrades; null when no trades
    val avgR: BigDecimal?,              // mean per-trade R; null when no trade has usable risk
    val rUnavailable: Int,              // trades excluded from avgR (missing/zero risk)
    val maxDrawdownUsd: BigDecimal,
    val maxDrawdownPct: BigDecimal,     // 0..1, scale 4
)

data class SymbolPerformance(val symbol: String, val summary: PerformanceSummary)

data class EquityPoint(val timestampMs: Long, val accountValue: BigDecimal, val drawdownPct: BigDecimal)
```

- [ ] **Step 2: Write the failing tests.** Create `PerformanceAnalyticsTest.kt` covering the edge cases:
```kotlin
package ru.driics.aitrade.domain.analytics

import ru.driics.aitrade.domain.ports.ClosedTradeRow
import ru.driics.aitrade.domain.ports.EntryRiskRow
import ru.driics.aitrade.domain.ports.PnlSnapshotRow
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceAnalyticsTest {
    private val svc = PerformanceAnalytics()
    private fun close(sym: String, pnl: String, close: Long, inst: String = "$sym-USDT-SWAP", open: Long = 0L) =
        ClosedTradeRow("pos-$sym-$close", inst, sym, "long", BigDecimal(pnl), open, close)

    @Test fun `empty input yields a zeroed summary`() {
        val s = svc.summary(emptyList(), emptyList(), emptyList())
        assertEquals(0, s.totalTrades)
        assertEquals(0, BigDecimal.ZERO.compareTo(s.winRate))
        assertNull(s.profitFactor); assertNull(s.expectancyUsd); assertNull(s.avgR)
    }

    @Test fun `win-rate, profit factor, expectancy over wins and losses`() {
        val closes = listOf(close("BTC","30",3), close("BTC","-10",2), close("ETH","-20",1), close("ETH","0",4))
        val s = svc.summary(closes, emptyList(), emptyList())
        assertEquals(4, s.totalTrades); assertEquals(1, s.wins); assertEquals(2, s.losses); assertEquals(1, s.scratches)
        assertEquals(0, BigDecimal("0.2500").compareTo(s.winRate))          // 1 win / 4
        assertEquals(0, BigDecimal("30").compareTo(s.grossProfit))
        assertEquals(0, BigDecimal("30").compareTo(s.grossLoss))
        assertEquals(0, BigDecimal("1").compareTo(s.profitFactor!!))         // 30 / 30
        assertEquals(0, BigDecimal("0").compareTo(s.netRealizedPnl))         // 30-10-20+0
    }

    @Test fun `profit factor is null when there are no losses`() {
        val s = svc.summary(listOf(close("BTC","5",1), close("BTC","7",2)), emptyList(), emptyList())
        assertNull(s.profitFactor)
        assertEquals(0, BigDecimal("1.0000").compareTo(s.winRate))
    }

    @Test fun `avg-R links closes to the latest prior PLACED entry risk; missing risk excluded`() {
        val closes = listOf(close("BTC","20",closeMs(2)), close("ETH","-5",closeMs(2)))
        val entries = listOf(
            EntryRiskRow("BTC-USDT-SWAP", closeMs(1), BigDecimal("10")),  // BTC risk 10 → R=+2
            EntryRiskRow("ETH-USDT-SWAP", closeMs(1), null),              // ETH risk missing → excluded
        )
        val s = svc.summary(closes, entries, emptyList())
        assertEquals(1, s.rUnavailable)
        assertEquals(0, BigDecimal("2.0000").compareTo(s.avgR!!))           // only BTC: 20/10
    }

    @Test fun `max drawdown from the equity series in usd and pct`() {
        val series = listOf(
            PnlSnapshotRow(1, BigDecimal("100")), PnlSnapshotRow(2, BigDecimal("120")),
            PnlSnapshotRow(3, BigDecimal("90")),  PnlSnapshotRow(4, BigDecimal("110")),
        )
        val s = svc.summary(emptyList(), emptyList(), series)
        assertEquals(0, BigDecimal("30").compareTo(s.maxDrawdownUsd))       // 120 → 90
        assertEquals(0, BigDecimal("0.2500").compareTo(s.maxDrawdownPct))   // 30/120
    }

    @Test fun `equityCurve carries running drawdown pct`() {
        val pts = svc.equityCurve(listOf(PnlSnapshotRow(1, BigDecimal("100")), PnlSnapshotRow(2, BigDecimal("80"))))
        assertEquals(2, pts.size)
        assertEquals(0, BigDecimal("0.0000").compareTo(pts[0].drawdownPct))
        assertEquals(0, BigDecimal("0.2000").compareTo(pts[1].drawdownPct)) // (100-80)/100
    }

    @Test fun `bySymbol groups independently`() {
        val closes = listOf(close("BTC","10",1), close("ETH","-3",2), close("BTC","-4",3))
        val out = svc.bySymbol(closes, emptyList(), emptyList()).associateBy { it.symbol }
        assertEquals(2, out["BTC"]!!.summary.totalTrades)
        assertEquals(1, out["ETH"]!!.summary.totalTrades)
        assertTrue(out["ETH"]!!.summary.losses == 1)
    }

    private fun closeMs(n: Long) = n
}
```

- [ ] **Step 3: Run; verify fail.** Run: `./gradlew.bat test --tests "*PerformanceAnalyticsTest*"`. Expected: FAIL — class missing.

- [ ] **Step 4: Implement the service.** Create `PerformanceAnalytics.kt`:
```kotlin
package ru.driics.aitrade.domain.analytics

import ru.driics.aitrade.domain.ports.ClosedTradeRow
import ru.driics.aitrade.domain.ports.EntryRiskRow
import ru.driics.aitrade.domain.ports.PnlSnapshotRow
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Pure realized-performance metrics over journal rows. No DB, no Spring. R links each close to the most
 * recent PLACED entry for the same instId at/before its close (one-position-per-symbol assumption).
 */
class PerformanceAnalytics {

    private val mc = MathContext(20, RoundingMode.HALF_UP)
    private val ratioScale = 4

    fun summary(
        closes: List<ClosedTradeRow>,
        entries: List<EntryRiskRow>,
        snapshots: List<PnlSnapshotRow>,
    ): PerformanceSummary {
        val wins = closes.filter { it.realizedPnl.signum() > 0 }
        val losses = closes.filter { it.realizedPnl.signum() < 0 }
        val scratches = closes.size - wins.size - losses.size

        val grossProfit = wins.fold(BigDecimal.ZERO) { a, c -> a + c.realizedPnl }
        val grossLoss = losses.fold(BigDecimal.ZERO) { a, c -> a + c.realizedPnl }.abs()
        val net = closes.fold(BigDecimal.ZERO) { a, c -> a + c.realizedPnl }

        val winRate = if (closes.isEmpty()) BigDecimal.ZERO
            else BigDecimal(wins.size).divide(BigDecimal(closes.size), ratioScale, RoundingMode.HALF_UP)
        val profitFactor = if (grossLoss.signum() == 0) null else grossProfit.divide(grossLoss, mc)
        val avgWin = if (wins.isEmpty()) null else grossProfit.divide(BigDecimal(wins.size), mc)
        val avgLoss = if (losses.isEmpty()) null else grossLoss.divide(BigDecimal(losses.size), mc)
        val expectancy = if (closes.isEmpty()) null else net.divide(BigDecimal(closes.size), mc)

        // avg-R: for each close, find the latest PLACED entry for its instId with recordedAt <= closeTime.
        val rValues = closes.mapNotNull { c ->
            val risk = entries
                .filter { it.instId == c.instId && it.recordedAtMs <= c.closeTimeMs && it.riskUsd != null && it.riskUsd.signum() > 0 }
                .maxByOrNull { it.recordedAtMs }
                ?.riskUsd
            risk?.let { c.realizedPnl.divide(it, mc) }
        }
        val avgR = if (rValues.isEmpty()) null
            else rValues.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(rValues.size), ratioScale, RoundingMode.HALF_UP)

        val (ddUsd, ddPct) = maxDrawdown(snapshots)

        return PerformanceSummary(
            totalTrades = closes.size, wins = wins.size, losses = losses.size, scratches = scratches,
            winRate = winRate, grossProfit = grossProfit, grossLoss = grossLoss, netRealizedPnl = net,
            profitFactor = profitFactor, avgWin = avgWin, avgLoss = avgLoss, expectancyUsd = expectancy,
            avgR = avgR, rUnavailable = closes.size - rValues.size,
            maxDrawdownUsd = ddUsd, maxDrawdownPct = ddPct,
        )
    }

    fun bySymbol(
        closes: List<ClosedTradeRow>, entries: List<EntryRiskRow>, snapshots: List<PnlSnapshotRow>,
    ): List<SymbolPerformance> =
        closes.groupBy { it.symbol }.toSortedMap().map { (sym, rows) ->
            // Drawdown is account-wide, not per-symbol → pass empty snapshots so per-symbol dd is zero.
            SymbolPerformance(sym, summary(rows, entries.filter { it.instId.substringBefore("-") == sym }, emptyList()))
        }

    fun equityCurve(snapshots: List<PnlSnapshotRow>): List<EquityPoint> {
        var peak = BigDecimal.ZERO
        return snapshots.sortedBy { it.timestampMs }.map { s ->
            if (s.accountValue > peak) peak = s.accountValue
            val dd = if (peak.signum() <= 0) BigDecimal.ZERO
                else (peak - s.accountValue).divide(peak, ratioScale, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
            EquityPoint(s.timestampMs, s.accountValue, dd)
        }
    }

    private fun maxDrawdown(snapshots: List<PnlSnapshotRow>): Pair<BigDecimal, BigDecimal> {
        var peak = BigDecimal.ZERO
        var maxUsd = BigDecimal.ZERO
        var maxPct = BigDecimal.ZERO
        for (s in snapshots.sortedBy { it.timestampMs }) {
            if (s.accountValue > peak) peak = s.accountValue
            val ddUsd = peak - s.accountValue
            if (ddUsd > maxUsd) maxUsd = ddUsd
            if (peak.signum() > 0) {
                val ddPct = ddUsd.divide(peak, ratioScale, RoundingMode.HALF_UP)
                if (ddPct > maxPct) maxPct = ddPct
            }
        }
        return maxUsd to maxPct
    }
}
```

- [ ] **Step 5: Run; verify pass.** Run: `./gradlew.bat test --tests "*PerformanceAnalyticsTest*"`. Expected: PASS (all cases).

- [ ] **Step 6: Commit.**
```bash
git add src/main/kotlin/ru/driics/aitrade/domain/analytics/ src/test/kotlin/ru/driics/aitrade/domain/analytics/
git commit -m "feat(analytics): pure PerformanceAnalytics metric service"
```

---

## Task 7: `AnalyticsController` (read-only JSON API)

**Files:**
- Create: `controller/AnalyticsController.kt`
- Modify: `config/ApplicationWiring.kt` (wire the controller's collaborators if not component-scanned)
- Test: `src/test/kotlin/ru/driics/aitrade/controller/AnalyticsControllerTest.kt`

- [ ] **Step 1: Write the failing controller test (MockMvc, standalone).** Create `AnalyticsControllerTest.kt`:
```kotlin
package ru.driics.aitrade.controller

import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.driics.aitrade.domain.analytics.PerformanceAnalytics
import ru.driics.aitrade.domain.ports.*
import java.math.BigDecimal
import kotlin.test.Test

class AnalyticsControllerTest {
    private val query = mockk<TradeJournalQueryPort>(relaxed = true)
    private fun mvc(enabled: Boolean): MockMvc =
        MockMvcBuilders.standaloneSetup(AnalyticsController(query, PerformanceAnalytics(), enabled)).build()

    @Test fun `summary returns enabled=true and metrics`() {
        every { query.closedTrades(any()) } returns listOf(
            ClosedTradeRow("p1","BTC-USDT-SWAP","BTC","long", BigDecimal("10"), 0, 1))
        every { query.entryRisks(any()) } returns emptyList()
        every { query.pnlSnapshots(any()) } returns emptyList()

        mvc(enabled = true).perform(get("/api/analytics/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.data.totalTrades").value(1))
            .andExpect(jsonPath("$.data.wins").value(1))
    }

    @Test fun `summary returns enabled=false envelope when journal disabled`() {
        mvc(enabled = false).perform(get("/api/analytics/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.data.totalTrades").value(0))
    }
}
```

- [ ] **Step 2: Run; verify fail.** Run: `./gradlew.bat test --tests "*AnalyticsControllerTest*"`. Expected: FAIL — controller missing.

- [ ] **Step 3: Implement the controller.** Create `AnalyticsController.kt`:
```kotlin
package ru.driics.aitrade.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.driics.aitrade.domain.analytics.EquityPoint
import ru.driics.aitrade.domain.analytics.PerformanceAnalytics
import ru.driics.aitrade.domain.analytics.PerformanceSummary
import ru.driics.aitrade.domain.analytics.SymbolPerformance
import ru.driics.aitrade.domain.ports.AnalyticsFilter
import ru.driics.aitrade.domain.ports.ClosedTradeRow
import ru.driics.aitrade.domain.ports.TradeJournalQueryPort

/** Read-only realized-performance API over the trade journal. Empty `enabled:false` envelope when off. */
@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(
    private val query: TradeJournalQueryPort,
    private val analytics: PerformanceAnalytics,
    @Value("\${trade-journal.enabled:false}") private val journalEnabled: Boolean,
) {
    data class Envelope<T>(val enabled: Boolean, val data: T)

    private fun filter(from: Long?, to: Long?, mode: String?, symbol: String?) =
        AnalyticsFilter(fromMs = from, toMs = to, mode = mode, symbol = symbol)

    @GetMapping("/summary")
    fun summary(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) mode: String?, @RequestParam(required = false) symbol: String?,
    ): Envelope<PerformanceSummary> {
        val f = filter(from, to, mode, symbol)
        return Envelope(journalEnabled, analytics.summary(query.closedTrades(f), query.entryRisks(f), query.pnlSnapshots(f)))
    }

    @GetMapping("/by-symbol")
    fun bySymbol(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) mode: String?,
    ): Envelope<List<SymbolPerformance>> {
        val f = filter(from, to, mode, null)
        return Envelope(journalEnabled, analytics.bySymbol(query.closedTrades(f), query.entryRisks(f), query.pnlSnapshots(f)))
    }

    @GetMapping("/equity-curve")
    fun equityCurve(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?, @RequestParam(required = false) mode: String?,
    ): Envelope<List<EquityPoint>> {
        val f = filter(from, to, mode, null)
        return Envelope(journalEnabled, analytics.equityCurve(query.pnlSnapshots(f)))
    }

    @GetMapping("/trades")
    fun trades(
        @RequestParam(required = false) from: Long?, @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) mode: String?, @RequestParam(required = false) symbol: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
    ): Envelope<List<ClosedTradeRow>> {
        val all = query.closedTrades(filter(from, to, mode, symbol)).sortedByDescending { it.closeTimeMs }
        return Envelope(journalEnabled, all.drop(offset).take(limit))
    }
}
```
> `PerformanceAnalytics` is wired as a `@Bean` already? It's a plain class — either annotate it `@Component`/`@Service` or add a `@Bean` in `ApplicationWiring`. Simplest: add `@Bean fun performanceAnalytics() = PerformanceAnalytics()` in `ApplicationWiring`. The `query` bean comes from Task 5's wiring (NoOp when disabled). With the NoOp query, every list is empty, so the `enabled:false` envelope yields zeroed metrics automatically.

- [ ] **Step 4: Wire `PerformanceAnalytics`.** In `ApplicationWiring.kt` add:
```kotlin
@Bean fun performanceAnalytics() = ru.driics.aitrade.domain.analytics.PerformanceAnalytics()
```
(or annotate the class `@Service` — pick whichever matches how sibling pure services like `OrderSizingPolicy` are provided in this codebase).

- [ ] **Step 5: Run; verify pass.** Run: `./gradlew.bat test --tests "*AnalyticsControllerTest*"`. Expected: PASS.

- [ ] **Step 6: Full suite green.** Run: `./gradlew.bat test`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit.**
```bash
git add src/main/kotlin/ru/driics/aitrade/controller/AnalyticsController.kt src/main/kotlin/ru/driics/aitrade/config/ApplicationWiring.kt src/test/kotlin/ru/driics/aitrade/controller/AnalyticsControllerTest.kt
git commit -m "feat(analytics): read-only /api/analytics JSON endpoints"
```

---

## Final verification

- [ ] Run the full suite: `./gradlew.bat test` → BUILD SUCCESSFUL.
- [ ] Manual smoke (optional, requires a Postgres + `trade-journal.enabled=true`): boot in PAPER mode, let a cycle run, `GET /api/analytics/summary` → `enabled:true` with populated metrics after at least one position closes.
- [ ] Confirm with journal **disabled** (default): `GET /api/analytics/summary` → `200 {"enabled":false,"data":{...zeros...}}`.

---

## Self-Review notes (author check against the spec)

- **Spec coverage:** close capture (T3+T5), `trade_close` table + `recordClose` (T2), `risk_usd` fix (T1), read port (T4), pure metrics incl. avg-R linkage + drawdown (T6), 4 endpoints + `enabled` envelope (T7), fail-safe capture (T2/T5), idempotent dedup via UNIQUE `pos_id` + high-water rehydrate (T2/T5). ✔
- **PAPER-only data boundary** (spec §4.4) is inherent — no code path simulates closes; documented in T5 doc + final smoke note.
- **Type consistency:** `ClosedTradeRow`/`EntryRiskRow`/`PnlSnapshotRow`/`AnalyticsFilter` defined in T4 and consumed unchanged in T6/T7; `JournaledClose` defined T2, used T2/T5; `ClosedPosition` defined T3, used T3/T5; `PerformanceSummary`/`SymbolPerformance`/`EquityPoint` defined T6, used T7. ✔
- **Known confirm-points flagged inline** (not placeholders): `OkxApiResponse` ctor arg names, `OkxPositionHistoryData.direction`, `TradeJournalPersistenceConfig` conditional structure, how the orchestrator infrastructure holder threads `tradeJournal`. Each has concrete code + the exact file to check.
- **Deferred (spec §11):** R-denominator (B), HTML/Grafana, rejection analytics, pyramiding — intentionally out of scope.

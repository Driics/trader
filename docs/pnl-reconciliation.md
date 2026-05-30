# B0 — Daily Realized-PnL Reconciliation Runbook

> **Why this exists.** The daily-loss kill-switch trusts `OkxExchangeAdapter.getTodaysRealizedPnlUsd`,
> which sums the `pnl` field of `/account/bills` over today's bills. That interpretation has **never been
> verified against live OKX** — it is the one money-handling assumption the whole cap rests on. This
> harness reconciles that number against an **independent** OKX source (`/account/positions-history`
> `realizedPnl`) using **real captured data, offline, with no live funds at risk** — so you can confirm
> the cap measures losses correctly *before* trusting it.

The single unverified knob is `OkxExchangeAdapter.realizedPnlContribution()`. Everything below exists to
tell you whether that knob is right and, if not, exactly how it's wrong.

---

## The loop

```
capture (live, once)  ->  review breakdown  ->  interpret gap  ->  fix the knob  ->  lock tolerance
```

### 1. Capture real data (hits live OKX, places no orders)

The `capture-okx` profile forces `trading.auto-execute=false` and pushes the scheduler out, so **no order
can be placed while capturing**. It reuses the app's signed OKX clients, so you don't hand-sign anything.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=capture-okx'
```

It writes three files to `./data/okx-capture/` (gitignored — this is real account data) and stops the JVM:

| File | Contents |
|------|----------|
| `bills.json` | today's `/account/bills` (`OkxBillData[]`) |
| `positions-history.json` | today's closed positions (`OkxPositionHistoryData[]`) |
| `capture-meta.json` | `dayStartMs` = the exact UTC-midnight window the live cap would use |

> Capture **after** a day on which you actually had closed positions (paper/demo trading is fine — OKX
> demo has its own bills). An empty day reconciles trivially and proves nothing.

### 2. (Optional) record the OKX UI figure

Open the OKX app/web, read **today's realized PnL** for the same UTC day, and drop it into
`./data/okx-capture/oracle.properties`:

```properties
# Your eyeballed figure from the OKX UI (USD). Used as a sanity backstop only.
ui.realizedPnlUsd=-37.42
```

### 3. Run the reconciliation

```powershell
.\gradlew.bat test --tests "*OkxPnlReconciliationFixtureTest*" --info
```

- With **no capture present**, the test is **skipped** (it never blocks a normal build).
- With a capture, it prints the breakdown to stdout **and** writes
  `./data/okx-capture/reconciliation-report.txt` — read that file (gradle often swallows stdout).

### 4. Read the breakdown and interpret

The report ends with a `-- DELTAS --` and a `-- READ-ME --` section. The three outcomes:

| What you see | Meaning | Action |
|---|---|---|
| `bills.pnl - oracle ≈ 0` | The cap's input already matches OKX's realized PnL. | **Done.** The knob is correct. Lock a tolerance (step 6). |
| `bills.(pnl+fee) - oracle ≈ 0` (but `bills.pnl - oracle` ≠ 0) | OKX folds fees into `realizedPnl`; the cap omits them, so it **under-reports losses** by the fee total. | Make `realizedPnlContribution()` add `fee` (step 5). |
| neither ≈ 0 | Something else (unmodeled bill types, non-USD currencies, windowing). | Inspect the per-`(type/subType/ccy)` rows; check the `realizedPnl by ccy` line for non-USD settlement. Resolve before trusting the cap. |

Also check:
- **`realizedPnl identity residual (want ~0)`** — if non-zero, OKX's `realizedPnl` has a component we don't
  model; `OkxPositionHistoryData` needs another field before the oracle is trustworthy.
- **`realizedPnl by ccy`** — any non-USD currency means the cap is summing mixed currencies as if USD.

### 5. Fix the knob (only if a gap was found)

Edit the single isolated assumption in `OkxExchangeAdapter`:

```kotlin
private fun OkxBillData.realizedPnlContribution(): BigDecimal =
    pnl.toBigDecimalOrNull() ?: BigDecimal.ZERO
```

e.g. to include fees: `(pnl.toBigDecimalOrNull() ?: ZERO) + (fee.toBigDecimalOrNull() ?: ZERO)`; or filter
by `type`/`subType` to exclude funding bills. Add a synthetic regression case to
`OkxExchangeAdapterPnlTest` encoding the corrected behavior, then re-run the loop from step 3.

### 6. Lock the tolerance (turn verification into a regression guard)

Once the gap is closed, commit the acceptable divergence to `oracle.properties`:

```properties
max.abs.delta.usd=0.01
```

Now `OkxPnlReconciliationFixtureTest` **fails** if a future change makes the bills-derived PnL diverge
from the OKX oracle by more than that — for as long as the capture stays in `./data/okx-capture/`.

---

## What this harness is and isn't

- **Is:** an offline, deterministic reconciliation of the bills/PnL path against an independent oracle,
  driven by real captured payloads you supply.
- **Isn't:** a market-replay or strategy backtester. Scope is the money-measurement path only.

The reconciliation machinery itself is covered by deterministic synthetic tests in
`PnlReconciliationTest` (no live data needed), so the harness is trustworthy before you feed it real data.

> ⚠️ Until step 4 shows `bills.pnl - oracle ≈ 0` (or the knob is fixed so it does), **do not trust the
> daily-loss cap with live funds** — a wrong sign or omitted-fee gap means it can fail to halt trading on a
> real losing day.

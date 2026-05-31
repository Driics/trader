# Phase 3 — enabling the streaming entry-price source (`useStreamingEntryPrice`)

The WS streaming layer (tickers/order/position flows) is built and connected, but by default the live
decision loop still sizes orders off a **REST** `getLastPrice` read. Phase 3 added the ability to size
off the **fresh real-time WS price** instead, behind a flag that defaults **off** and falls back to
REST whenever the socket is stale/disconnected or the WS price diverges too far from REST — so it is
never worse than today.

This runbook is how you turn it on safely, per environment.

## What the flag does

`trading.use-streaming-entry-price` (`TRADING_USE_STREAMING_ENTRY_PRICE`, default `false`):

- **off** — entry sizing uses the REST `getLastPrice` (today's behaviour, unchanged).
- **on** — `buildPlan` calls `getFreshPrice(instId, 5s)`; if the socket is connected AND the last tick
  is ≤ 5s old AND the WS price is within **0.5%** (50 bps) of REST, that WS price sizes the order.
  Any of those failing → REST fallback (a warning is logged when a *fresh* WS price is rejected for
  diverging too far, which flags a misbehaving feed).

The freshness/divergence guards are pure and unit-tested (`OkxStreamingFreshnessTest`,
`EntryPriceSelectionTest`, `PriceParityDeltaTest`); the end-to-end wiring (flag on → WS price reaches
sizing) is pinned by the `Phase 3 - ...` tests in `ExecuteAiDecisionsUseCaseTest`.

## Why it is OFF by default

It changes the price that sizes a real order — a money-path behaviour change. Like every other
money-path toggle in this system (`demo-mode`, `auto-execute`, `confirm-live`, `trade-journal.enabled`)
it ships off and is enabled as a deliberate, per-environment decision, only once the evidence below is
in hand. "The flag exists and the build is green" is **not** the same as "verified in this environment".

## Run-before-you-flip checklist

1. **Code wiring proven** — ✅ done (`ExecuteAiDecisionsUseCaseTest` `Phase 3 - *`).
2. **Flag surfaced + default off** — ✅ done (`application.yml` → `trading.use-streaming-entry-price`).
3. **Live parity evidence** — run the probe below in the target environment and confirm a small,
   stable WS-vs-REST delta. **Do this before flipping.**

## Step 1 — gather parity evidence (`PublicWsParityProbeTest`)

A public-data-only probe: it connects the real OKX public websocket, waits for a fresh tick, and prints
the WS-vs-REST `deltaBps` over a handful of samples. It reads only public data and places no orders.
Keep `OPENROUTER_API_KEYS` a **dummy** value: the context boots (it needs a non-empty value) and the one
scheduled cycle that may fire cannot make a real, billed AI call.

```powershell
$env:WS_PARITY_PROBE="1"
$env:OPENROUTER_API_KEYS="dummy"   # context needs a non-empty value to boot; keep it a dummy so no real AI call is billed
.\gradlew.bat --rerun-tasks test --tests '*PublicWsParityProbeTest*'
```

> Note: this is a full-`@SpringBootTest` probe, so it needs enough memory to boot the Spring context.
> If the test JVM reports "insufficient memory ... malloc failed", stop stale Gradle daemons
> (`.\gradlew.bat --stop`) and/or raise the test heap before re-running — it is an environment limit,
> not a probe failure.

Reading the output:

```
ws-parity BTC-USDT-SWAP: connected, first fresh price=... (waited 1500ms)
  sample[0] ws=... rest=... deltaBps=2.41
  ...
ws-parity BTC-USDT-SWAP: PASS (6 paired samples, maxDeltaBps=8.13)
```

- **deltaBps stays small (single/low-double digits) and stable** → the WS feed agrees with REST; safe to enable.
- **"no fresh WS price within 30000ms"** → the socket did not connect/tick from this environment. The
  feature would safely fall back to REST anyway, but **do not enable** — there is no evidence here.
- **deltaBps large or jumpy** → investigate the feed before enabling.

(Optional, stronger) With real keys and `auto-execute=true` + `demo-mode=true`, the live loop also logs
`price-parity <instId>: ws=… rest=… deltaBps=…` per actionable cycle — useful as a second confirmation,
but it needs AI budget and produces sparse samples, which is why the probe above is the primary path.

## Step 2 — enable it

Once the probe shows a small, stable delta in that environment:

```
TRADING_USE_STREAMING_ENTRY_PRICE=true
```

Restart the app. Watch the logs for any `WS entry price ... rejected: deltaBps=...` warnings — a steady
stream of those means the feed is diverging and the flag should go back off.

## Rollback

Unset `TRADING_USE_STREAMING_ENTRY_PRICE` (or set it `false`) and restart. The loop returns to REST
sizing immediately; nothing else depends on the flag.

## Scope note

This discharges the **public**-WS gate (entry pricing). The private-WS keepalive verification
(order/position streams) is a separate concern and not a prerequisite for this flag.

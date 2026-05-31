package ru.driics.aitrade.domain.backtest

import java.math.BigDecimal

/**
 * Per-symbol contract specification, mirroring the OKX instrument fields the live order path uses
 * (`ctVal`, `ctValCcy`, `lotSz`, `minSz`). Lives in config — never forked into a "simpler" sim model —
 * so the backtest sizes positions through the exact same [ru.driics.aitrade.domain.services.OrderSizingPolicy]
 * the production system does (invariant 5). For BTC-USDT-SWAP: ctVal=0.01, ctValCcy="BTC" (the contract
 * value is denominated in the base coin, so it takes the coin-quote sizing branch), lotSz=0.1, minSz=0.1.
 */
data class InstrumentSpec(
    val ctVal: BigDecimal,
    val ctValCcy: String,
    val lotSz: BigDecimal,
    val minSz: BigDecimal,
)

/**
 * All knobs the deterministic backtest needs. Kept as plain data (no Spring) so the engine stays pure
 * and unit-testable in isolation; the `@Profile("backtest")` runner (batch 5) constructs this from
 * application properties.
 *
 * @property startingEquityUsd realized equity at t0.
 * @property takerFeePct taker fee as a fraction (e.g. 0.0005 = 5 bps), charged on entry and exit notional.
 * @property marginBufferPct extra margin headroom as a fraction, forwarded to the sizing policy.
 * @property riskPerTradePct fraction of equity risked per trade when a decision carries no explicit
 *   quantity: `coinQty = equity * riskPerTradePct / |fill - stop|`.
 * @property warmupBars number of leading bars during which the engine builds state but does NOT call the
 *   strategy — indicators (RSI/MACD/EMA) need history, and a cold-start RSI of 0 would false-trigger.
 * @property intradayWindow cap on the length of the intraday lists exposed in [ru.driics.aitrade.domain.model.MarketState]
 *   (mirrors the live rolling window; does not affect scalar indicator math, which uses the full prefix).
 * @property instruments per-symbol contract specs; a symbol absent here cannot be traded.
 * @property minLev / [maxLev] leverage clamp handed to the sizing policy.
 */
data class BacktestConfig(
    val startingEquityUsd: BigDecimal,
    val takerFeePct: BigDecimal,
    val marginBufferPct: BigDecimal,
    val riskPerTradePct: BigDecimal,
    val warmupBars: Int,
    val intradayWindow: Int,
    val instruments: Map<String, InstrumentSpec>,
    val minLev: Int = 5,
    val maxLev: Int = 40,
)

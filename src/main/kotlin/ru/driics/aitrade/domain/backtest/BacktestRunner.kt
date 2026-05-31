package ru.driics.aitrade.domain.backtest

import ru.driics.aitrade.domain.strategy.RsiReversionStrategy
import ru.driics.aitrade.domain.strategy.Strategy
import java.math.BigDecimal

/**
 * Pure orchestration over [JsonlCandleParser] + [BacktestEngine] — takes a JSONL string (not a file), so
 * it stays testable without I/O. The harness (a env-gated test) adds the file read and report write.
 *
 * [defaultConfig] ships BTC-USDT-SWAP contract specs and middle-of-the-road cost/risk knobs; for other
 * instruments, pass a tailored [BacktestConfig] (the specs MUST match the symbol — see [InstrumentSpec]).
 */
object BacktestRunner {

    /** BTC-USDT-SWAP defaults: ctVal 0.01 BTC/contract (coin-quote branch), lot/min 0.1 contracts. */
    fun defaultConfig(
        symbol: String,
        startingEquityUsd: BigDecimal = BigDecimal("10000"),
    ): BacktestConfig = BacktestConfig(
        startingEquityUsd = startingEquityUsd,
        takerFeePct = BigDecimal("0.0005"),      // 5 bps OKX taker
        marginBufferPct = BigDecimal("0.05"),
        riskPerTradePct = BigDecimal("0.01"),    // risk 1% of equity per trade
        warmupBars = 30,                          // enough history for RSI/MACD/EMA to be meaningful
        intradayWindow = 300,
        instruments = mapOf(
            symbol to InstrumentSpec(
                ctVal = BigDecimal("0.01"),
                ctValCcy = "BTC",
                lotSz = BigDecimal("0.1"),
                minSz = BigDecimal("0.1"),
            ),
        ),
        minLev = 5,
        maxLev = 40,
    )

    fun run(
        jsonl: String,
        symbol: String,
        strategy: Strategy = RsiReversionStrategy(),
        config: BacktestConfig = defaultConfig(symbol),
    ): BacktestResult {
        val bars = JsonlCandleParser.parse(jsonl)
        return BacktestEngine(strategy, config).run(symbol, bars)
    }
}

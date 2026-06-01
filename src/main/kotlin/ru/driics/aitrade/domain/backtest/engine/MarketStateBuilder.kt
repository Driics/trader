package ru.driics.aitrade.domain.backtest.engine

import ru.driics.aitrade.domain.backtest.core.Bar
import ru.driics.aitrade.domain.model.AccountInfo
import ru.driics.aitrade.domain.model.CurrencyMarketData
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.model.Position
import ru.driics.aitrade.domain.services.IndicatorCalculator

/**
 * Turns a trailing bar window ending at the decision bar (for a single [symbol]) into the same
 * [MarketState] the live decision loop hands a [ru.driics.aitrade.domain.strategy.Strategy]. Pure and
 * standalone so it is testable apart from the engine.
 *
 * NO LOOK-AHEAD (invariant 1) lives here: the caller passes only bars up to and including the decision
 * bar, and everything below is derived solely from them. The engine bounds the window to
 * `indicatorLookback` trailing bars (keeps the run O(n) and matches the live rolling window), so the
 * scalar indicators use that window, not the unbounded history; the intraday lists are further truncated
 * to the last [intradayWindow] entries. `minutesSinceStart` is therefore relative to the window start,
 * not the global series start — no strategy depends on it in v1.
 */
object MarketStateBuilder {

    /**
     * @param symbol the instrument the [bars] belong to.
     * @param bars the prefix `bars[0..i]`, ascending by time; its last element is the decision bar.
     * @param stepIndex `i` — surfaced as `invocationCount`.
     * @param account synthesized account snapshot for this bar (see engine; strategies must not depend
     *   on live-only fields — sharpeRatio is null).
     * @param positions open positions mapped to the domain shape (live-only fields left null).
     * @param intradayWindow cap on intraday list length.
     */
    fun build(
        symbol: String,
        bars: List<Bar>,
        stepIndex: Int,
        account: AccountInfo,
        positions: List<Position>,
        intradayWindow: Int,
    ): MarketState {
        require(bars.isNotEmpty()) { "MarketStateBuilder requires at least one bar" }
        val closes = bars.map { it.close }
        val last = bars.last()

        val currentEma20 = IndicatorCalculator.calculateEMA(closes, 20)
        val currentMacd = IndicatorCalculator.calculateMACD(closes)
        val currentRsi7 = IndicatorCalculator.calculateRSI(closes, 7)

        val currency = CurrencyMarketData(
            symbol = symbol,
            currentPrice = last.close,
            currentEma20 = currentEma20,
            currentMacd = currentMacd,
            currentRsi7 = currentRsi7,
            intradayPrices = closes.takeLastWindow(intradayWindow),
            intradayEma20 = IndicatorCalculator.calculateProgressiveEMA(closes, 20).takeLastWindow(intradayWindow),
            intradayMacd = IndicatorCalculator.calculateProgressiveMACD(closes).takeLastWindow(intradayWindow),
            intradayRsi7 = IndicatorCalculator.calculateProgressiveRSI(closes, 7).takeLastWindow(intradayWindow),
            intradayRsi14 = IndicatorCalculator.calculateProgressiveRSI(closes, 14).takeLastWindow(intradayWindow),
        )

        return MarketState(
            timestamp = last.timestampMs,
            minutesSinceStart = (last.timestampMs - bars.first().timestampMs) / 60_000,
            invocationCount = stepIndex.toLong(),
            currencies = mapOf(symbol to currency),
            account = account,
            positions = positions,
        )
    }

    private fun <T> List<T>.takeLastWindow(window: Int): List<T> =
        if (window <= 0 || size <= window) this else takeLast(window)
}

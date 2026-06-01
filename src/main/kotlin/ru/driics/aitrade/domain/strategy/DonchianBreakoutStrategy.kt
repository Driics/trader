package ru.driics.aitrade.domain.strategy

import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

/**
 * A deterministic trend-following / breakout strategy — the structural opposite of mean-reversion. It
 * goes LONG when the close breaks ABOVE the highest of the prior [channel] closes (a Donchian-style
 * channel breakout) and SHORT when it breaks BELOW the lowest, otherwise HOLD. Stops/targets are fixed
 * fractions of price; a wide [targetFraction] partly compensates for the engine's lack of a trailing
 * stop (trend edge comes from fat-tailed winners, which a fixed take-profit caps — read results with that
 * in mind).
 *
 * Uses the close series in [ru.driics.aitrade.domain.model.CurrencyMarketData.intradayPrices] (whose last
 * element is the current bar's close), so it needs `channel + 1` bars of history before it acts.
 */
class DonchianBreakoutStrategy(
    private val channel: Int = 20,
    private val stopFraction: BigDecimal = BigDecimal("0.02"),
    private val targetFraction: BigDecimal = BigDecimal("0.06"), // 3:1 — give trend winners room
    private val leverage: Int = 5,
) : Strategy {

    override val name: String = "donchian($channel, sl=$stopFraction, tp=$targetFraction)"

    override fun decide(state: MarketState): List<StrategyDecision> =
        state.currencies.values.mapNotNull { md ->
            val price = md.currentPrice
            if (price.signum() <= 0) return@mapNotNull null

            val closes = md.intradayPrices
            // last element is the current close; we need `channel` PRIOR closes to form the channel.
            if (closes.size < channel + 1) return@mapNotNull StrategyDecision(md.symbol, AiSignal.HOLD)
            val prior = closes.subList(closes.size - 1 - channel, closes.size - 1)
            val priorHigh = prior.maxOrNull()!!
            val priorLow = prior.minOrNull()!!

            when {
                price > priorHigh -> longDecision(md.symbol, price)
                price < priorLow -> shortDecision(md.symbol, price)
                else -> StrategyDecision(md.symbol, AiSignal.HOLD)
            }
        }

    private fun longDecision(symbol: String, price: BigDecimal) = StrategyDecision(
        symbol = symbol,
        signal = AiSignal.BUY,
        stopLoss = price * (BigDecimal.ONE - stopFraction),
        takeProfit = price * (BigDecimal.ONE + targetFraction),
        leverage = leverage,
    )

    private fun shortDecision(symbol: String, price: BigDecimal) = StrategyDecision(
        symbol = symbol,
        signal = AiSignal.SELL,
        stopLoss = price * (BigDecimal.ONE + stopFraction),
        takeProfit = price * (BigDecimal.ONE - targetFraction),
        leverage = leverage,
    )
}

package ru.driics.aitrade.domain.strategy

import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.MarketState
import java.math.BigDecimal

/**
 * A simple deterministic mean-reversion strategy: when 7-period RSI is oversold go LONG, when
 * overbought go SHORT, otherwise HOLD. Stops and targets are fixed fractions of the current price,
 * giving a constant reward:risk. Pure — depends only on the snapshot it is handed.
 *
 * It exists to (a) validate the backtest engine end-to-end and (b) be a real, non-AI baseline behind
 * the [Strategy] seam. Every non-HOLD decision carries a stop, satisfying the engine's leverage rule.
 */
class RsiReversionStrategy(
    private val oversold: BigDecimal = BigDecimal("30"),
    private val overbought: BigDecimal = BigDecimal("70"),
    private val stopFraction: BigDecimal = BigDecimal("0.02"),   // 2%
    private val targetFraction: BigDecimal = BigDecimal("0.04"), // 4% -> 2:1 reward:risk
    private val leverage: Int = 5,
) : Strategy {

    override val name: String = "rsi-reversion($oversold/$overbought)"

    override fun decide(state: MarketState): List<StrategyDecision> =
        state.currencies.values.mapNotNull { md ->
            val price = md.currentPrice
            if (price.signum() <= 0) return@mapNotNull null
            when {
                md.currentRsi7 <= oversold -> longDecision(md.symbol, price)
                md.currentRsi7 >= overbought -> shortDecision(md.symbol, price)
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

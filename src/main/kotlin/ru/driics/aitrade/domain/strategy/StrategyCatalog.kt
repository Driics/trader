package ru.driics.aitrade.domain.strategy

import java.math.BigDecimal

/**
 * The canonical set of strategy variants used by the sweep and the walk-forward validator, so both select
 * from the SAME grid. Keeping it in one place is what makes "the grid winner" a well-defined thing to
 * then validate out-of-sample.
 */
object StrategyCatalog {

    fun rsiAndDonchianGrid(): List<Pair<String, Strategy>> = buildList {
        for ((os, ob) in listOf(20 to 80, 25 to 75, 30 to 70, 35 to 65, 40 to 60)) {
            add("rsi $os/$ob 2:1" to RsiReversionStrategy(BigDecimal(os), BigDecimal(ob), BigDecimal("0.02"), BigDecimal("0.04")))
        }
        for ((s, t, n) in listOf(
            Triple("0.02", "0.02", "1:1"),
            Triple("0.02", "0.06", "3:1"),
            Triple("0.015", "0.045", "3:1 tight"),
            Triple("0.03", "0.045", "1.5:1"),
        )) {
            add("rsi 30/70 sl$s/$t $n" to RsiReversionStrategy(BigDecimal("30"), BigDecimal("70"), BigDecimal(s), BigDecimal(t)))
        }
        for ((ch, tp, n) in listOf(
            Triple(20, "0.04", "2:1"),
            Triple(20, "0.06", "3:1"),
            Triple(20, "0.10", "5:1"),
            Triple(55, "0.06", "3:1"),
            Triple(10, "0.06", "3:1"),
        )) {
            add("donchian $ch sl0.02/$tp $n" to DonchianBreakoutStrategy(ch, BigDecimal("0.02"), BigDecimal(tp)))
        }
    }
}

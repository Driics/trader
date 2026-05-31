package ru.driics.aitrade.application.ai

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import ru.driics.aitrade.domain.model.OkxInstrumentInfo
import java.math.BigDecimal

/**
 * Characterization tests pinning the CURRENT ActionGuard validation (TP/SL direction + distance, leverage
 * range, quantity sign), written before any cleanup so it can be proven behaviour-preserving. Entry 50000,
 * tickSz 0.1 → min TP/SL distance = 5 ticks = 0.5; leverage range [1, 40] (props maxLeverage default).
 */
class ActionGuardTest {

    private val guard = ActionGuard(TradingProperties(currencies = listOf("BTC")))

    private val inst = OkxInstrumentInfo(
        instId = "BTC-USDT-SWAP", instType = "SWAP",
        ctVal = "0.01", ctValCcy = "BTC", lotSz = "1", minSz = "1", tickSz = "0.1",
    )

    private val entry = BigDecimal("50000")

    private fun plan(signal: AiSignal, tp: String?, sl: String?, lev: Int? = 5, qty: String? = null) =
        AiTradeSignalArgs(
            coin = "BTC", signal = signal,
            profitTarget = tp?.let { BigDecimal(it) }, stopLoss = sl?.let { BigDecimal(it) },
            leverage = lev, quantity = qty?.let { BigDecimal(it) },
        )

    private fun rejected(r: ActionGuard.ValidationResult) = r is ActionGuard.ValidationResult.Rejected

    @Test
    fun `valid BUY (TP above, SL below, far enough, leverage in range) passes`() {
        val r = guard.validate(plan(AiSignal.BUY, tp = "51000", sl = "49000"), inst, entry)
        assertTrue(r is ActionGuard.ValidationResult.Valid)
    }

    @Test
    fun `valid SELL (TP below, SL above) passes`() {
        val r = guard.validate(plan(AiSignal.SELL, tp = "49000", sl = "51000"), inst, entry)
        assertTrue(r is ActionGuard.ValidationResult.Valid)
    }

    @Test
    fun `BUY with TP below entry is rejected as not favorable`() {
        assertTrue(rejected(guard.validate(plan(AiSignal.BUY, tp = "49000", sl = "49000"), inst, entry)))
    }

    @Test
    fun `TP closer than the minimum tick distance is rejected`() {
        assertTrue(rejected(guard.validate(plan(AiSignal.BUY, tp = "50000.2", sl = "49000"), inst, entry)))
    }

    @Test
    fun `leverage above the cap is rejected`() {
        assertTrue(rejected(guard.validate(plan(AiSignal.BUY, tp = "51000", sl = "49000", lev = 50), inst, entry)))
    }

    @Test
    fun `non-positive quantity is rejected`() {
        assertTrue(rejected(guard.validate(plan(AiSignal.BUY, tp = "51000", sl = "49000", qty = "-1"), inst, entry)))
    }
}

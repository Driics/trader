package ru.driics.aitrade.application.ai

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.domain.model.AiSignal
import ru.driics.aitrade.domain.model.AiTradeSignalArgs
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Characterization tests pinning the CURRENT behaviour of the confidence + cooldown gate, written before
 * any refactor so the refactor can be proven behaviour-preserving. Defaults: minConfidence 0.60, cooldown 5m.
 */
class ConfidenceCalibratorTest {

    private val clock = Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC)
    private val props = TradingProperties(currencies = listOf("BTC"))

    private fun calibrator() = ConfidenceCalibrator(props, clock)

    private fun signal(conf: String?, sig: AiSignal = AiSignal.BUY, coin: String = "BTC") =
        AiTradeSignalArgs(coin = coin, signal = sig, confidence = conf?.let { BigDecimal(it) })

    private fun accepted(r: ConfidenceCalibrator.CalibrationResult) =
        r is ConfidenceCalibrator.CalibrationResult.Accepted

    @Test
    fun `accepts a confident signal that is not in cooldown`() {
        assertTrue(accepted(calibrator().shouldAccept(signal("0.90"))))
    }

    @Test
    fun `rejects below-threshold confidence`() {
        assertTrue(!accepted(calibrator().shouldAccept(signal("0.50"))))
    }

    @Test
    fun `treats missing confidence as zero and rejects it`() {
        assertTrue(!accepted(calibrator().shouldAccept(signal(null))))
    }

    @Test
    fun `confidence exactly at the threshold is accepted (inclusive)`() {
        assertTrue(accepted(calibrator().shouldAccept(signal("0.60"))))
    }

    @Test
    fun `rejects a BUY for a symbol within its post-trade cooldown`() {
        val c = calibrator()
        c.recordTrade("BTC")
        assertTrue(!accepted(c.shouldAccept(signal("0.90"))))
    }

    @Test
    fun `HOLD is not subject to cooldown`() {
        val c = calibrator()
        c.recordTrade("BTC")
        assertTrue(accepted(c.shouldAccept(signal("0.90", AiSignal.HOLD))))
    }
}

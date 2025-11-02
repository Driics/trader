package ru.driics.aitrade.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.driics.aitrade.model.OkxInstrumentInfo
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class OkxTradingService(
    private val okxHttpClient: OkxHttpClient
) {
    companion object {
        private val log = KotlinLogging.logger {  }
    }

    fun loadInstrument(instId: String): OkxInstrumentInfo? =
        okxHttpClient.getSwapInstrument(instId)

    fun setCrossLeverage(instId: String, leverage: Int): Boolean =
        okxHttpClient.setLeverageCross(instId, leverage)

    fun placeMarketOrderWithTpSl(
        instId: String,
        side: String,
        szContracts: BigDecimal,
        tpPx: BigDecimal?,
        slPx: BigDecimal?,
        tickSz: BigDecimal,
        clOrdId: String
    ): Pair<Boolean, String?> {
        val tpStr = tpPx?.let { quantize(it, tickSz).toPlainString() }
        val slStr = slPx?.let { quantize(it, tickSz).toPlainString() }
        val safeTag = sanitizeTag("ai-signal")

        val res = okxHttpClient.placeMarketOrderWithAttach(
            instId = instId,
            side = side,
            tdMode = "cross",
            szContracts = stripTrailingZeros(szContracts),
            tpPx = tpStr,
            slPx = slStr,
            posSide = null, // if you later switch to long/short mode, pass "long"/"short" here
            clOrdId = clOrdId,
            tag = safeTag
        )
        val ok = (res?.sCode == "0")
        if (!ok) {
            log.warn("Order not accepted {}: sCode={}, sMsg={}", clOrdId, res?.sCode, res?.sMsg)
        }
        return ok to res?.ordId
    }

    private fun quantize(px: BigDecimal, tick: BigDecimal): BigDecimal {
        if (tick.compareTo(BigDecimal.ZERO) == 0) return px
        // round to nearest tick
        val steps = px.divide(tick, 0, RoundingMode.HALF_UP)
        return steps.multiply(tick).stripTrailingZeros()
    }

    private fun stripTrailingZeros(x: BigDecimal): String =
        x.stripTrailingZeros().toPlainString()

    private fun sanitizeTag(raw: String?, maxLen: Int = 16, fallback: String = "AISIGNAL"): String? {
        val cleaned = (raw ?: fallback).filter { it.isLetterOrDigit() }.uppercase()
        val trimmed = cleaned.take(maxLen)
        return trimmed.ifBlank { null }
    }
}
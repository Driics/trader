package ru.driics.aitrade.infra.exchange.websocket.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import java.math.BigDecimal

// ----- Envelope -----

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsArg(
    val channel: String,
    @field:JsonProperty("instId") val instId: String? = null,
    @field:JsonProperty("instType") val instType: String? = null
)

/**
 * Generic WS envelope. Use TypeReferences from OkxWsTypeRefs to parse.
 * Example: objectMapper.readValue(text, OkxWsTypeRefs.ticker)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsEnvelope<T>(
    val event: String? = null,
    val op: String? = null,
    val arg: OkxWsArg? = null,
    val data: List<T>? = null,
    val code: String? = null,
    val msg: String? = null
)

// ----- Public: tickers -----

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsTickerUpdate(
    @field:JsonProperty("instId") val instId: String,
    @field:JsonProperty("last") val last: String,
    @field:JsonProperty("lastSz") val lastSz: String? = null,
    @field:JsonProperty("askPx") val askPx: String? = null,
    @field:JsonProperty("askSz") val askSz: String? = null,
    @field:JsonProperty("bidPx") val bidPx: String? = null,
    @field:JsonProperty("bidSz") val bidSz: String? = null,
    @field:JsonProperty("open24h") val open24h: String? = null,
    @field:JsonProperty("high24h") val high24h: String? = null,
    @field:JsonProperty("low24h") val low24h: String? = null,
    @field:JsonProperty("volCcy24h") val volCcy24h: String? = null,
    @field:JsonProperty("vol24h") val vol24h: String? = null,
    @field:JsonProperty("ts") val ts: String
) {
    fun priceOrNull(): BigDecimal? = last.toBigDecimalOrNull()
    fun tsMillisOrNow(clock: java.time.Clock = java.time.Clock.systemUTC()): Long = ts.toLongOrNull() ?: clock.instant().toEpochMilli()
}

// ----- Public: candles (object-shaped payload) -----

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsCandleUpdate(
    // Note: instId is provided in the envelope arg; caller may set it if needed.
    @field:JsonProperty("ts") val ts: String,
    @field:JsonProperty("o") val o: String,
    @field:JsonProperty("h") val h: String,
    @field:JsonProperty("l") val l: String,
    @field:JsonProperty("c") val c: String,
    @field:JsonProperty("vol") val vol: String,
    @field:JsonProperty("volCcy") val volCcy: String? = null,
    @field:JsonProperty("confirm") val confirm: String? = null
) {
    fun isConfirmed(): Boolean = confirm == "1"
    fun tsMillisOrZero(): Long = ts.toLongOrNull() ?: 0L
    fun closeOrZero(): BigDecimal = c.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

// ----- Private: orders -----

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsOrderUpdate(
    @field:JsonProperty("instId") val instId: String,
    @field:JsonProperty("ordId") val ordId: String,
    @field:JsonProperty("clOrdId") val clOrdId: String? = null,
    @field:JsonProperty("px") val px: String? = null,
    @field:JsonProperty("sz") val sz: String,
    @field:JsonProperty("ordType") val ordType: String,
    @field:JsonProperty("side") val side: String,
    @field:JsonProperty("posSide") val posSide: String? = null,
    @field:JsonProperty("state") val state: String,
    @field:JsonProperty("avgPx") val avgPx: String? = null,
    @field:JsonProperty("accFillSz") val accFillSz: String? = null,
    @field:JsonProperty("fillPx") val fillPx: String? = null,
    @field:JsonProperty("fillSz") val fillSz: String? = null,
    @field:JsonProperty("fillTime") val fillTime: String? = null,
    @field:JsonProperty("ts") val ts: String
) {
    fun avgPxOrNull(): BigDecimal? = avgPx?.toBigDecimalOrNull()
    fun tsMillisOrZero(): Long = ts.toLongOrNull() ?: 0L
}

// ----- Private: positions -----

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsPositionUpdate(
    @field:JsonProperty("instId") val instId: String,
    @field:JsonProperty("posId") val posId: String? = null,
    @field:JsonProperty("pos") val pos: String,
    @field:JsonProperty("posSide") val posSide: String,
    @field:JsonProperty("avgPx") val avgPx: String,
    @field:JsonProperty("upl") val upl: String,
    @field:JsonProperty("uplRatio") val uplRatio: String? = null,
    @field:JsonProperty("lever") val lever: String,
    @field:JsonProperty("liqPx") val liqPx: String? = null,
    @field:JsonProperty("markPx") val markPx: String? = null,
    @field:JsonProperty("margin") val margin: String? = null,
    @field:JsonProperty("ts") val ts: String
) {
    fun posOrZero(): BigDecimal = pos.toBigDecimalOrNull() ?: BigDecimal.ZERO
    fun avgPxOrZero(): BigDecimal = avgPx.toBigDecimalOrNull() ?: BigDecimal.ZERO
    fun uplOrZero(): BigDecimal = upl.toBigDecimalOrNull() ?: BigDecimal.ZERO
    fun tsMillisOrZero(): Long = ts.toLongOrNull() ?: 0L
}

// ----- Private: account -----

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsAccountDetail(
    @field:JsonProperty("ccy") val ccy: String,
    @field:JsonProperty("availBal") val availBal: String,
    @field:JsonProperty("cashBal") val cashBal: String,
    @field:JsonProperty("frozenBal") val frozenBal: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxWsAccountUpdate(
    @field:JsonProperty("totalEq") val totalEq: String,
    @field:JsonProperty("isoEq") val isoEq: String? = null,
    @field:JsonProperty("adjEq") val adjEq: String? = null,
    @field:JsonProperty("ordFroz") val ordFroz: String? = null,
    @field:JsonProperty("details") val details: List<OkxWsAccountDetail>? = null,
    @field:JsonProperty("ts") val ts: String
) {
    fun totalEqOrZero(): BigDecimal = totalEq.toBigDecimalOrNull() ?: BigDecimal.ZERO
    fun tsMillisOrZero(): Long = ts.toLongOrNull() ?: 0L
}

// ----- Type references for parsing envelopes -----

@Suppress("UnnecessaryObjectReference")
object OkxWsTypeRefs {
    val ticker: TypeReference<OkxWsEnvelope<OkxWsTickerUpdate>> =
        object : TypeReference<OkxWsEnvelope<OkxWsTickerUpdate>>() {}
    val candle: TypeReference<OkxWsEnvelope<OkxWsCandleUpdate>> =
        object : TypeReference<OkxWsEnvelope<OkxWsCandleUpdate>>() {}
    val orders: TypeReference<OkxWsEnvelope<OkxWsOrderUpdate>> =
        object : TypeReference<OkxWsEnvelope<OkxWsOrderUpdate>>() {}
    val positions: TypeReference<OkxWsEnvelope<OkxWsPositionUpdate>> =
        object : TypeReference<OkxWsEnvelope<OkxWsPositionUpdate>>() {}
    val account: TypeReference<OkxWsEnvelope<OkxWsAccountUpdate>> =
        object : TypeReference<OkxWsEnvelope<OkxWsAccountUpdate>>() {}
}
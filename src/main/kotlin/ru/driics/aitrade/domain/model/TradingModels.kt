package ru.driics.aitrade.domain.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal

// ---------- AI Signal Enum ----------

enum class AiSignal(val value: String) {
    BUY("buy"),
    SELL("sell"),
    HOLD("hold");

    @JsonValue
    fun toJson(): String = value

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromJson(value: String): AiSignal = when (value.lowercase()) {
            "buy" -> BUY
            "sell" -> SELL
            "hold" -> HOLD
            else -> throw IllegalArgumentException("Unknown signal: $value")
        }
    }
}

// ---------- AI decision DTOs ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class AiTradeSignalArgs(
    val coin: String,
    val signal: AiSignal,
    val quantity: BigDecimal? = null,
    @JsonProperty("profit_target")
    val profitTarget: BigDecimal? = null,
    @JsonProperty("stop_loss")
    val stopLoss: BigDecimal? = null,
    @JsonProperty("invalidation_condition")
    val invalidationCondition: String? = null,
    val leverage: Int? = null,
    val confidence: BigDecimal? = null,
    @JsonProperty("risk_usd")
    val riskUsd: BigDecimal? = null,
    val justification: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AiTradeEnvelope(
    @JsonProperty("trade_signal_args")
    val args: AiTradeSignalArgs
)

typealias AiTradeDecisionMap = Map<String, AiTradeEnvelope>

// ---------- OKX: instruments we need ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxInstrumentInfo(
    @JsonProperty("instId") val instId: String,
    @JsonProperty("instType") val instType: String?,
    @JsonProperty("ctVal") val ctVal: String?,
    @JsonProperty("ctValCcy") val ctValCcy: String?,
    @JsonProperty("lotSz") val lotSz: String?,
    @JsonProperty("minSz") val minSz: String?,
    @JsonProperty("tickSz") val tickSz: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxPublicInstrumentsApiResponse(
    val code: String = "0",
    val msg: String = "",
    val data: List<OkxInstrumentInfo> = emptyList()
) {
    fun isSuccess() = code == "0"
    fun firstOrNull(): OkxInstrumentInfo? = data.firstOrNull()
}

// ---------- OKX: place order result ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxPlaceOrderData(
    @JsonProperty("ordId") val ordId: String? = null,
    @JsonProperty("clOrdId") val clOrdId: String? = null,
    @JsonProperty("sCode") val sCode: String? = null,
    @JsonProperty("sMsg") val sMsg: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxPlaceOrderApiResponse(
    val code: String = "0",
    val msg: String = "",
    val data: List<OkxPlaceOrderData> = emptyList()
) {
    fun isSuccess() = code == "0" && data.firstOrNull()?.sCode == "0"
    fun firstOrNull(): OkxPlaceOrderData? = data.firstOrNull()
}

// ---------- Execution result per symbol ----------

enum class AIAction {
    PLACED,
    SKIPPED
}

data class AiTradeExecutionResult(
    val symbol: String,
    val action: AIAction,
    val message: String,
    val instId: String? = null,
    val clOrdId: String? = null,
    val ordId: String? = null,
    val requestedContracts: BigDecimal? = null,
    val placedContracts: BigDecimal? = null
)
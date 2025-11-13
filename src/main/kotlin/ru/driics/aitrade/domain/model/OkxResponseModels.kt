package ru.driics.aitrade.domain.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ============================================
// OKX API Wrapper Response Classes
// ============================================

/**
 * Generic OKX API response wrapper
 * Standard structure: {"code": "0", "msg": "", "data": [...]}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxApiResponse<T>(
    @JsonProperty("code")
    val code: String = "0",

    @JsonProperty("msg")
    val message: String = "",

    @JsonProperty("data")
    val data: List<T> = emptyList()
) {
    fun isSuccess(): Boolean = code == "0"

    fun getFirstOrNull(): T? = data.firstOrNull()
}

/**
 * Special wrapper for candles endpoint
 * Candles return array of arrays: [[timestamp, open, high, low, close, volume, volumeCcy], ...]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxCandlesApiResponse(
    @JsonProperty("code")
    val code: String = "0",

    @JsonProperty("msg")
    val message: String = "",

    @JsonProperty("data")
    val data: List<List<String>> = emptyList()
) {
    fun isSuccess(): Boolean = code == "0"

    fun toCandles(): List<OkxCandleResponse> {
        return data.mapNotNull { values ->
            if (values.size < 7) return@mapNotNull null
            try {
                OkxCandleResponse(
                    timestamp = values[0],
                    open = values[1],
                    high = values[2],
                    low = values[3],
                    close = values[4],
                    volume = values[5],
                    volumeCcy = values[6]
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

// Type aliases for cleaner code
typealias OkxTickerApiResponse = OkxApiResponse<OkxTickerResponse>
typealias OkxFundingApiResponse = OkxApiResponse<OkxFundingResponse>
typealias OkxOpenInterestApiResponse = OkxApiResponse<OkxOpenInterestResponse>
typealias OkxPositionApiResponse = OkxApiResponse<OkxPositionResponse>

/**
 * Account balance wrapper (data structure is slightly different)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxAccountApiResponse(
    @JsonProperty("code")
    val code: String = "0",

    @JsonProperty("msg")
    val message: String = "",

    @JsonProperty("data")
    val data: List<OkxAccountData> = emptyList()
) {
    fun isSuccess(): Boolean = code == "0"

    fun getFirstOrNull(): OkxAccountData? = data.firstOrNull()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxAccountData(
    @JsonProperty("totalEq")
    val totalEquity: String = "0",

    @JsonProperty("availEq")
    val availableEquityUsd: String = "0",

    @JsonProperty("availBal")
    val availableBalance: String = "0",

    @JsonProperty("cashBal")
    val cashBalance: String = "0",

    @JsonProperty("upl")
    val unrealizedPnl: String = "0",

    @JsonProperty("details")
    val details: List<OkxAccountDetail> = emptyList()
)
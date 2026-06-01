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

/**
 * A single account bill (ledger entry) from GET /api/v5/account/bills (last 7 days).
 * Only the fields relevant to realized-PnL accounting are modeled; unknowns are ignored.
 *
 * `pnl` carries the profit/loss of the event in the settlement currency (negative = loss).
 * `ts` is the millisecond epoch the bill was generated. `billId` is the pagination cursor
 * (passed as `after` to fetch older records).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxBillData(
    @JsonProperty("billId")
    val billId: String = "",

    @JsonProperty("ts")
    val timestamp: String = "0",

    @JsonProperty("pnl")
    val pnl: String = "0",

    @JsonProperty("fee")
    val fee: String = "0",

    @JsonProperty("ccy")
    val currency: String = "",

    @JsonProperty("type")
    val type: String = "",

    @JsonProperty("subType")
    val subType: String = "",

    @JsonProperty("instType")
    val instType: String = ""
)

/**
 * A single closed-position record from GET /api/v5/account/positions-history.
 *
 * Used as the INDEPENDENT reconciliation oracle for the bills-derived daily realized PnL (B0).
 * Per OKX, `realizedPnl = pnl + fee + fundingFee + liqPenalty`, so summing [realizedPnl] over
 * positions closed since UTC midnight is OKX's own realized-PnL figure for the day — a deterministic
 * cross-check against [OkxBillData]-based accounting. `uTime` is the millisecond epoch the position
 * was last updated (closed). Only fields relevant to PnL reconciliation are modeled; unknowns ignored.
 *
 * !! Like the bills path, this is UNVERIFIED against live OKX until reconciled in paper trading. !!
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxPositionHistoryData(
    @JsonProperty("instType")
    val instType: String = "",

    @JsonProperty("instId")
    val instId: String = "",

    @JsonProperty("mgnMode")
    val marginMode: String = "",

    @JsonProperty("type")
    val closeType: String = "",

    @JsonProperty("posId")
    val posId: String = "",

    @JsonProperty("cTime")
    val createdTime: String = "0",

    @JsonProperty("uTime")
    val updatedTime: String = "0",

    @JsonProperty("realizedPnl")
    val realizedPnl: String = "0",

    @JsonProperty("pnl")
    val pnl: String = "0",

    @JsonProperty("fee")
    val fee: String = "0",

    @JsonProperty("fundingFee")
    val fundingFee: String = "0",

    @JsonProperty("liqPenalty")
    val liqPenalty: String = "0",

    @JsonProperty("ccy")
    val currency: String = "",

    @JsonProperty("direction")
    val direction: String = "",

    @JsonProperty("lever")
    val leverage: String = "0",

    @JsonProperty("openAvgPx")
    val openAvgPx: String = "0",

    @JsonProperty("closeAvgPx")
    val closeAvgPx: String = "0"
)
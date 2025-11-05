package ru.driics.aitrade.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

// Market Data Models
@JsonIgnoreProperties(ignoreUnknown = true)
data class CurrencyMarketData(
    val symbol: String,
    val currentPrice: BigDecimal,
    val currentEma20: BigDecimal,
    val currentMacd: BigDecimal,
    val currentRsi7: BigDecimal,
    val openInterest: BigDecimal? = null,
    val fundingRate: BigDecimal? = null,
    val intradayPrices: List<BigDecimal> = emptyList(),
    val intradayEma20: List<BigDecimal> = emptyList(),
    val intradayMacd: List<BigDecimal> = emptyList(),
    val intradayRsi7: List<BigDecimal> = emptyList(),
    val intradayRsi14: List<BigDecimal> = emptyList(),
    val ema20_4h: BigDecimal? = null,
    val ema50_4h: BigDecimal? = null,
    val atr3_4h: BigDecimal? = null,
    val atr14_4h: BigDecimal? = null,
    val volume4h: BigDecimal? = null,
    val avgVolume4h: BigDecimal? = null,
    val macd4h: List<BigDecimal> = emptyList(),
    val rsi14_4h: List<BigDecimal> = emptyList()
)

// Account and Position Models
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountInfo(
    val totalReturn: BigDecimal,
    val availableCash: BigDecimal,
    val accountValue: BigDecimal,
    val sharpeRatio: BigDecimal? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Position(
    val symbol: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val liquidationPrice: BigDecimal? = null,
    val unrealizedPnl: BigDecimal,
    val leverage: Int? = null,
    val exitPlan: ExitPlan? = null,
    val confidence: BigDecimal? = null,
    val riskUsd: BigDecimal? = null,
    val slOid: Long? = null,
    val tpOid: Long? = null,
    val waitForFill: Boolean = false,
    val entryOid: Long? = null,
    val notionalUsd: BigDecimal? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExitPlan(
    val profitTarget: BigDecimal? = null,
    val stopLoss: BigDecimal? = null,
    val invalidationCondition: String? = null
)

// Aggregated Market State
@JsonIgnoreProperties(ignoreUnknown = true)
data class MarketState(
    val timestamp: Long,
    val minutesSinceStart: Long,
    val invocationCount: Long,
    val currencies: Map<String, CurrencyMarketData>,
    val account: AccountInfo,
    val positions: List<Position>
)

// OKX API Response Models
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxCandleResponse(
    @JsonProperty("ts")
    val timestamp: String,
    @JsonProperty("o")
    val open: String,
    @JsonProperty("h")
    val high: String,
    @JsonProperty("l")
    val low: String,
    @JsonProperty("c")
    val close: String,
    @JsonProperty("vol")
    val volume: String,
    @JsonProperty("volCcy")
    val volumeCcy: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxTickerResponse(
    @JsonProperty("instId")
    val instrumentId: String,
    @JsonProperty("last")
    val lastPrice: String,
    @JsonProperty("askPx")
    val askPrice: String,
    @JsonProperty("bidPx")
    val bidPrice: String,
    @JsonProperty("ts")
    val timestamp: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxPositionResponse(
    @JsonProperty("instId")
    val instrumentId: String,
    @JsonProperty("posId")
    val positionId: String,
    @JsonProperty("posSide")
    val positionSide: String,
    @JsonProperty("pos")
    val quantity: String,
    @JsonProperty("avgPx")
    val averagePrice: String,
    @JsonProperty("mgnMode")
    val marginMode: String,
    @JsonProperty("lever")
    val leverage: String,
    @JsonProperty("liqPx")
    val liquidationPrice: String,
    @JsonProperty("markPx")
    val markPrice: String,
    @JsonProperty("upl")
    val unrealizedPnl: String,
    @JsonProperty("uplRatio")
    val unrealizedPnlRatio: String,
    @JsonProperty("cTime")
    val createTime: String,
    @JsonProperty("uTime")
    val updateTime: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxAccountResponse(
    @JsonProperty("totalEq")
    val totalEquity: String,
    @JsonProperty("availBal")
    val availableBalance: String,
    @JsonProperty("cashBal")
    val cashBalance: String,
    @JsonProperty("upl")
    val unrealizedPnl: String,
    @JsonProperty("details")
    val details: List<OkxAccountDetail> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxAccountDetail(
    @JsonProperty("ccy")
    val currency: String,
    @JsonProperty("cashBal")
    val cashBalance: String,
    @JsonProperty("availBal")
    val availableBalance: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxFundingResponse(
    @JsonProperty("instId")
    val instrumentId: String,
    @JsonProperty("fundingRate")
    val fundingRate: String,
    @JsonProperty("nextFundingTime")
    val nextFundingTime: String,
    @JsonProperty("ts")
    val timestamp: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OkxOpenInterestResponse(
    @JsonProperty("instId")
    val instrumentId: String,
    @JsonProperty("oi")
    val openInterest: String,
    @JsonProperty("ts")
    val timestamp: String
)
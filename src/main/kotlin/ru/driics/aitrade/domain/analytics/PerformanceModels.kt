package ru.driics.aitrade.domain.analytics

import java.math.BigDecimal

data class PerformanceSummary(
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val scratches: Int,
    val winRate: BigDecimal,           // 0..1, scale 4; 0 when no decisive trades
    val grossProfit: BigDecimal,
    val grossLoss: BigDecimal,          // positive magnitude
    val netRealizedPnl: BigDecimal,
    val profitFactor: BigDecimal?,      // null when grossLoss == 0
    val avgWin: BigDecimal?,            // null when wins == 0
    val avgLoss: BigDecimal?,           // null when losses == 0
    val expectancyUsd: BigDecimal?,     // net / totalTrades; null when no trades
    val avgR: BigDecimal?,              // mean per-trade R; null when no trade has usable risk
    val rUnavailable: Int,              // trades excluded from avgR (missing/zero risk)
    val maxDrawdownUsd: BigDecimal,
    val maxDrawdownPct: BigDecimal,     // 0..1, scale 4
)

data class SymbolPerformance(val symbol: String, val summary: PerformanceSummary)

data class EquityPoint(val timestampMs: Long, val accountValue: BigDecimal, val drawdownPct: BigDecimal)

data class TradeWithR(
    val posId: String, val instId: String, val symbol: String, val side: String?,
    val realizedPnl: BigDecimal, val openTimeMs: Long, val closeTimeMs: Long, val r: BigDecimal?,
)

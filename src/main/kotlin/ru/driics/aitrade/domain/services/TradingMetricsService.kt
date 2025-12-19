package ru.driics.aitrade.domain.services

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Service
class TradingMetricsService(private val meterRegistry: MeterRegistry) {

    private val currentBalance = AtomicReference(BigDecimal.ZERO)
    private val pnlBySymbol = ConcurrentHashMap<String, Double>()

    init {
        // Gauge for total account balance (equity)
        Gauge.builder("trading.balance.total.usd", currentBalance) { it.get().toDouble() }
            .description("Total account equity in USD (USDT)")
            .register(meterRegistry)
    }

    fun updateAccountBalance(balance: BigDecimal) {
        currentBalance.set(balance)
    }

    fun recordOrderPlaced(symbol: String, side: String, type: String = "market") {
        Counter.builder("trading.orders.placed")
            .tag("symbol", symbol)
            .tag("side", side)
            .tag("type", type)
            .description("Total number of orders placed")
            .register(meterRegistry)
            .increment()
    }

    fun recordOrderRejected(symbol: String, reason: String) {
        Counter.builder("trading.orders.rejected")
            .tag("symbol", symbol)
            .tag("reason", reason)
            .description("Total number of orders rejected")
            .register(meterRegistry)
            .increment()
    }

    fun recordTradePnl(symbol: String, pnl: BigDecimal) {
        // 1. Record the individual trade PnL in a distribution summary (histogram)
        DistributionSummary.builder("trading.pnl.trade")
            .tag("symbol", symbol)
            .description("Distribution of realized PnL per trade")
            .register(meterRegistry)
            .record(pnl.toDouble())

        // 2. Update the cumulative PnL gauge for this symbol (approximate)
        pnlBySymbol.merge(symbol, pnl.toDouble()) { old, new -> old + new }
        
        // Ensure the gauge is registered (idempotent)
        Gauge.builder("trading.pnl.cumulative", pnlBySymbol) { it[symbol] ?: 0.0 }
            .tag("symbol", symbol)
            .description("Cumulative realized PnL for the symbol since restart")
            .register(meterRegistry)
    }

    fun recordAiSignal(symbol: String, signal: String, confidence: Double) {
        Counter.builder("ai.signals.total")
            .tag("symbol", symbol)
            .tag("signal", signal)
            .description("Total number of AI signals generated")
            .register(meterRegistry)
            .increment()

        DistributionSummary.builder("ai.confidence")
            .tag("symbol", symbol)
            .tag("signal", signal)
            .description("Distribution of AI confidence scores")
            .register(meterRegistry)
            .record(confidence)
    }
}

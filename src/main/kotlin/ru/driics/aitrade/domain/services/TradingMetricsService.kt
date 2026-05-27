package ru.driics.aitrade.domain.services

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.DoubleAdder

@Service
class TradingMetricsService(private val meterRegistry: MeterRegistry) {

    private val currentBalance = AtomicReference(BigDecimal.ZERO)
    private val cumulativePnlBySymbol = ConcurrentHashMap<String, DoubleAdder>()

    private val counters = ConcurrentHashMap<String, Counter>()
    private val summaries = ConcurrentHashMap<String, DistributionSummary>()

    init {
        registerBalanceGauge()
    }

    // ==================== Public API ====================

    fun updateAccountBalance(balance: BigDecimal) {
        currentBalance.set(balance)
    }

    fun recordOrderPlaced(symbol: String, side: String, type: String = DEFAULT_ORDER_TYPE) {
        counter(
            key = "order:placed:$symbol:$side:$type",
            name = Metrics.ORDERS_PLACED,
            description = "Total number of orders placed",
            tags = arrayOf("symbol" to symbol, "side" to side, "type" to type)
        ).increment()
    }

    fun recordOrderRejected(symbol: String, reason: String) {
        val normalizedReason = reason.sanitizeAsTag()

        counter(
            key = "order:rejected:$symbol:$normalizedReason",
            name = Metrics.ORDERS_REJECTED,
            description = "Total number of orders rejected",
            tags = arrayOf("symbol" to symbol, "reason" to normalizedReason)
        ).increment()
    }

    fun recordTradePnl(symbol: String, pnl: BigDecimal) {
        val pnlValue = pnl.toDouble()

        summary(
            key = "pnl:$symbol",
            name = Metrics.PNL_TRADE,
            description = "Distribution of realized PnL per trade",
            tags = arrayOf("symbol" to symbol)
        ).record(pnlValue)

        getOrCreateCumulativePnl(symbol).add(pnlValue)
    }

    fun recordAiSignal(symbol: String, signal: String, confidence: Double) {
        val baseKey = "ai:$symbol:$signal"
        val tags = arrayOf("symbol" to symbol, "signal" to signal)

        counter(
            key = baseKey,
            name = Metrics.AI_SIGNALS,
            description = "Total number of AI signals generated",
            tags = tags
        ).increment()

        summary(
            key = baseKey,
            name = Metrics.AI_CONFIDENCE,
            description = "Distribution of AI confidence scores",
            tags = tags
        ).record(confidence)
    }

    // ==================== Private Helpers ====================

    private fun registerBalanceGauge() {
        Gauge.builder(Metrics.BALANCE_TOTAL, currentBalance) { it.get().toDouble() }
            .description("Total account equity in USD (USDT)")
            .register(meterRegistry)
    }

    private fun counter(
        key: String,
        name: String,
        description: String,
        tags: Array<out Pair<String, String>>
    ): Counter = counters.computeIfAbsent(key) {
        Counter.builder(name)
            .description(description)
            .withTags(tags)
            .register(meterRegistry)
    }

    private fun summary(
        key: String,
        name: String,
        description: String,
        tags: Array<out Pair<String, String>>
    ): DistributionSummary = summaries.computeIfAbsent(key) {
        DistributionSummary.builder(name)
            .description(description)
            .withTags(tags)
            .register(meterRegistry)
    }

    private fun getOrCreateCumulativePnl(symbol: String): DoubleAdder =
        cumulativePnlBySymbol.computeIfAbsent(symbol) { sym ->
            DoubleAdder().also { adder ->
                Gauge.builder(Metrics.PNL_CUMULATIVE, adder, DoubleAdder::sum)
                    .tag("symbol", sym)
                    .description("Cumulative realized PnL for the symbol since restart")
                    .register(meterRegistry)
            }
        }

    // ==================== Extensions ====================

    private fun Counter.Builder.withTags(tags: Array<out Pair<String, String>>): Counter.Builder =
        apply { tags.forEach { (key, value) -> tag(key, value) } }

    private fun DistributionSummary.Builder.withTags(tags: Array<out Pair<String, String>>): DistributionSummary.Builder =
        apply { tags.forEach { (key, value) -> tag(key, value) } }

    private fun String.sanitizeAsTag(): String =
        take(MAX_TAG_LENGTH)
            .replace(TAG_SANITIZER_REGEX, "_")
            .lowercase()

    // ==================== Constants ====================

    private object Metrics {
        const val BALANCE_TOTAL = "trading.balance.total.usd"
        const val ORDERS_PLACED = "trading.orders.placed"
        const val ORDERS_REJECTED = "trading.orders.rejected"
        const val PNL_TRADE = "trading.pnl.trade"
        const val PNL_CUMULATIVE = "trading.pnl.cumulative"
        const val AI_SIGNALS = "ai.signals.total"
        const val AI_CONFIDENCE = "ai.confidence"
    }

    companion object {
        private const val MAX_TAG_LENGTH = 30
        private const val DEFAULT_ORDER_TYPE = "market"
        private val TAG_SANITIZER_REGEX = Regex("[^a-zA-Z0-9_\\-]")
    }
}
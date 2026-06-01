package ru.driics.aitrade.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@ConfigurationProperties(prefix = "trading.risk")
data class RiskGateProperties(
    var enabled: Boolean = true,

    @field:DecimalMin(value = "0.0", message = "Max daily loss must be non-negative")
    var maxDailyLossUsd: BigDecimal = BigDecimal("50"),

    @field:Min(value = 1, message = "Max concurrent positions must be at least 1")
    var maxConcurrentPositions: Int = 5,

    /**
     * Per-trade risk ceiling as a fraction of account equity (e.g. 0.02 = 2%). The position is sized so its
     * loss to the stop never exceeds this, even if the model self-reports a larger quantity or risk_usd —
     * making risk_usd authoritative and the model's quantity advisory. `<= 0` disables the cap (legacy:
     * the model's quantity is trusted uncapped).
     *
     * Intentionally independent of [enabled]: this per-trade sizing cap stays active even when the
     * portfolio gates ([maxDailyLossUsd], [maxConcurrentPositions]) are switched off, because one oversized
     * trade is a sizing concern, not a portfolio one. Disable it ONLY via this value (`<= 0`).
     */
    @field:DecimalMin(value = "0.0", message = "Max risk per trade pct must be non-negative")
    @field:DecimalMax(value = "1.0", message = "Max risk per trade pct must be at most 1.0 (100% of equity)")
    var maxRiskPerTradePct: BigDecimal = BigDecimal("0.02"),

    /** S2: path to the durable kill-switch JSON file (atomic temp+rename). */
    var killSwitchFile: String = "./data/kill-switch.json",
)

package ru.driics.aitrade.config

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

    /** S2: path to the durable kill-switch JSON file (atomic temp+rename). */
    var killSwitchFile: String = "./data/kill-switch.json",
)

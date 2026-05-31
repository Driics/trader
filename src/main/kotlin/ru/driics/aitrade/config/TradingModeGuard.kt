package ru.driics.aitrade.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import ru.driics.aitrade.common.logging.logger
import ru.driics.aitrade.domain.model.TradingMode

/**
 * Resolves and announces the effective [TradingMode] at startup, and fails fast on unsafe configurations
 * — so the execution mode is never a silent surprise (roadmap risk #2: "one bad env var = live orders").
 *
 * Refuses to start when:
 * - mode is LIVE (demo-mode=false AND okx.paper=false) but `trading.confirm-live` is not true — trading
 *   REAL funds takes two deliberate flags, not one;
 * - `okx.paper=true` but `okx.broker-id` is missing — the demo WebSocket login requires it, and the
 *   failure would otherwise surface later as an unrelated-looking WS error.
 */
@Component
class TradingModeGuard(
    private val tradingProperties: TradingProperties,
    private val okxProperties: OkxProperties,
) {
    private companion object {
        val log = logger<TradingModeGuard>()
    }

    @PostConstruct
    fun validateAndAnnounce() {
        // Triggered by okx.paper regardless of mode: paper routes the WS to wspap, which needs brokerId.
        check(!(okxProperties.paper && okxProperties.brokerId.isNullOrBlank())) {
            "okx.paper=true but okx.broker-id is not set — OKX demo WebSocket login requires it. " +
                "Set OKX_BROKER_ID (your OKX demo broker id) or disable paper mode."
        }

        when (TradingMode.resolve(tradingProperties.demoMode, okxProperties.paper)) {
            TradingMode.SIMULATION ->
                log.info { banner("SIMULATION", "orders are SIMULATED locally and never sent to OKX") }

            TradingMode.PAPER ->
                log.warn { banner("PAPER", "real orders are sent to OKX's DEMO environment (no real funds)") }

            TradingMode.LIVE -> {
                check(tradingProperties.confirmLive) {
                    "Refusing to start in LIVE mode (demo-mode=false, okx.paper=false) without explicit " +
                        "confirmation. Set trading.confirm-live=true (TRADING_CONFIRM_LIVE=true) to trade REAL " +
                        "funds, or set demo-mode=true (simulation) / okx.paper=true (paper)."
                }
                log.warn { banner("LIVE", "!!! REAL ORDERS WITH REAL FUNDS !!! (trading.confirm-live=true)") }
            }
        }
    }

    private fun banner(mode: String, detail: String): String =
        "\n========================================\n" +
            "  TRADING MODE: $mode\n" +
            "  $detail\n" +
            "========================================"
}

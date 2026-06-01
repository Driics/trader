package ru.driics.aitrade.domain.model

/**
 * The effective execution mode, derived from `trading.demo-mode` and `okx.paper`:
 * - [SIMULATION]: orders are simulated locally and NEVER reach OKX (safest; the default).
 * - [PAPER]: real orders are sent to OKX's DEMO environment (the `x-simulated-trading` header + demo
 *   keys) — no real funds.
 * - [LIVE]: real orders with REAL funds.
 */
enum class TradingMode {
    SIMULATION,
    PAPER,
    LIVE;

    companion object {
        fun resolve(demoMode: Boolean, paper: Boolean): TradingMode = when {
            demoMode -> SIMULATION
            paper -> PAPER
            else -> LIVE
        }
    }
}

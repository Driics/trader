package ru.driics.aitrade.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Unit specs for the per-trade risk cap — the pure sizing decision extracted from
 * ExecuteAiDecisionsUseCase.buildPlan so it can be pinned without the full placement path.
 *
 * The gap this closes: the live path used to trust the model's self-reported `quantity` uncapped, so a
 * single AI trade could risk 30%+ of the book (observed: 34% on a $10k book). The cap makes `riskUsd`
 * authoritative — sized from min(model riskUsd, maxRiskPct * equity), with the model's quantity treated
 * as advisory (honoured only when MORE conservative than the budget).
 */
class RiskCappedQuantityTest {

    private val pct = BigDecimal("0.02")        // 2% of equity
    private val equity = BigDecimal("10000")    // -> max risk per trade = $200
    private val entry = BigDecimal("76627")
    private val stop = BigDecimal("74000")      // risk-per-unit = 2627
    private val riskPerUnit = BigDecimal("2627")

    private fun resolve(
        modelQty: BigDecimal?,
        riskUsd: BigDecimal?,
        equityUsd: BigDecimal = equity,
        maxPct: BigDecimal = pct,
        stopLoss: BigDecimal? = stop,
    ) = resolveRiskCappedQuantity(modelQty, riskUsd, equityUsd, maxPct, entry, stopLoss)

    private fun budgetQty(riskUsd: String) =
        BigDecimal(riskUsd).divide(riskPerUnit, 8, RoundingMode.HALF_UP)

    @Test
    fun `oversized model quantity is clamped to the per-trade risk budget`() {
        // Model wants 5.0 BTC (~$13k risk to stop); cap = 2% of $10k = $200 -> ~0.0761 BTC.
        val q = resolve(modelQty = BigDecimal("5.0"), riskUsd = BigDecimal("1000"))
        assertEquals(0, budgetQty("200").compareTo(q), "must size to the \$200 cap, got $q")
        assertTrue(q < BigDecimal("5.0"), "must be clamped well below the model's quantity")
    }

    @Test
    fun `model risk_usd above the cap is clamped to the cap`() {
        // No model quantity; model asks for $1000 risk but the cap is $200.
        val q = resolve(modelQty = null, riskUsd = BigDecimal("1000"))
        assertEquals(0, budgetQty("200").compareTo(q), "risk_usd must be capped to \$200, got $q")
    }

    @Test
    fun `a conservative model quantity is respected (never inflated to the cap)`() {
        // Model's 0.01 BTC is smaller than the $200-budget quantity -> keep the model's caution.
        val q = resolve(modelQty = BigDecimal("0.01"), riskUsd = BigDecimal("1000"))
        assertEquals(0, BigDecimal("0.01").compareTo(q), "smaller model quantity must be kept, got $q")
    }

    @Test
    fun `within-budget risk_usd sizes from risk_usd unchanged`() {
        // $100 risk is below the $200 cap -> budget = $100, size = 100/2627 (unchanged from legacy).
        val q = resolve(modelQty = null, riskUsd = BigDecimal("100"))
        assertEquals(0, budgetQty("100").compareTo(q), "in-budget risk_usd must size unchanged, got $q")
    }

    @Test
    fun `no stop-loss cannot be risk-sized so it returns zero (skip, never uncapped)`() {
        val q = resolve(modelQty = BigDecimal("5.0"), riskUsd = BigDecimal("1000"), stopLoss = null)
        assertEquals(0, BigDecimal.ZERO.compareTo(q), "no stop -> cannot enforce risk -> 0, got $q")
    }

    @Test
    fun `zero equity yields a zero budget so the trade is skipped`() {
        // SIMULATION with no real keys reads $0 equity -> 2% of 0 = $0 -> no size (fail-safe).
        val q = resolve(modelQty = BigDecimal("5.0"), riskUsd = BigDecimal("1000"), equityUsd = BigDecimal.ZERO)
        assertEquals(0, BigDecimal.ZERO.compareTo(q), "zero equity -> zero size, got $q")
    }

    @Test
    fun `cap disabled (pct lessthanorequal 0) restores legacy behavior - model quantity wins`() {
        val q = resolve(modelQty = BigDecimal("5.0"), riskUsd = BigDecimal("1000"), maxPct = BigDecimal.ZERO)
        assertEquals(0, BigDecimal("5.0").compareTo(q), "disabled cap must use the model quantity, got $q")
    }

    @Test
    fun `cap disabled and no model quantity falls back to risk_usd sizing (legacy)`() {
        val q = resolve(modelQty = null, riskUsd = BigDecimal("1000"), maxPct = BigDecimal.ZERO)
        assertEquals(0, budgetQty("1000").compareTo(q), "disabled cap, no qty -> size from risk_usd, got $q")
    }

    @Test
    fun `negative equity (underwater account) yields zero size`() {
        // OKX can report negative equity during a liquidation cascade -> 2% of negative floors to 0 -> skip.
        val q = resolve(modelQty = BigDecimal("5.0"), riskUsd = BigDecimal("1000"), equityUsd = BigDecimal("-500"))
        assertEquals(0, BigDecimal.ZERO.compareTo(q), "negative equity -> zero size, got $q")
    }

    @Test
    fun `entry equal to stop (zero risk-per-unit) yields zero size`() {
        // |entry - stop| == 0 -> risk-per-unit undefined -> cannot risk-size -> skip.
        val q = resolve(modelQty = BigDecimal("5.0"), riskUsd = BigDecimal("1000"), stopLoss = entry)
        assertEquals(0, BigDecimal.ZERO.compareTo(q), "entry==stop -> zero size, got $q")
    }
}

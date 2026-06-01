package ru.driics.aitrade.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class TradingModeGuardTest {

    private fun guard(demoMode: Boolean, paper: Boolean, confirmLive: Boolean, brokerId: String? = "b") =
        TradingModeGuard(
            TradingProperties(demoMode = demoMode, confirmLive = confirmLive),
            OkxProperties(paper = paper, brokerId = brokerId),
        )

    @Test
    fun `SIMULATION starts cleanly`() {
        assertDoesNotThrow { guard(demoMode = true, paper = false, confirmLive = false).validateAndAnnounce() }
    }

    @Test
    fun `PAPER with a broker id starts cleanly`() {
        assertDoesNotThrow {
            guard(demoMode = false, paper = true, confirmLive = false, brokerId = "b").validateAndAnnounce()
        }
    }

    @Test
    fun `PAPER without a broker id is refused`() {
        val ex = assertThrows<IllegalStateException> {
            guard(demoMode = false, paper = true, confirmLive = false, brokerId = null).validateAndAnnounce()
        }
        assertTrue(ex.message!!.contains("broker-id"))
    }

    @Test
    fun `LIVE without confirm-live is refused`() {
        val ex = assertThrows<IllegalStateException> {
            guard(demoMode = false, paper = false, confirmLive = false).validateAndAnnounce()
        }
        assertTrue(ex.message!!.contains("LIVE"))
    }

    @Test
    fun `LIVE with confirm-live starts`() {
        assertDoesNotThrow { guard(demoMode = false, paper = false, confirmLive = true).validateAndAnnounce() }
    }
}

package ru.driics.aitrade.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.OkxProperties

class OkxAuthServiceTest {

    private fun props(paper: Boolean) = OkxProperties(
        apiKey = "key", secretKey = "secret", passphrase = "pass", paper = paper, brokerId = "b",
    )

    @Test
    fun `paper mode tags authenticated requests for OKX demo`() {
        val headers = OkxAuthService(props(paper = true)).createAuthHeaders("GET", "/api/v5/account/bills")
        assertEquals("1", headers["x-simulated-trading"], "paper mode must route auth calls to demo")
    }

    @Test
    fun `live mode omits the demo header`() {
        val headers = OkxAuthService(props(paper = false)).createAuthHeaders("GET", "/api/v5/account/bills")
        assertFalse(headers.containsKey("x-simulated-trading"), "live mode must NOT send the demo header")
        assertTrue(headers.containsKey("OK-ACCESS-SIGN"), "standard auth headers are still present")
    }
}

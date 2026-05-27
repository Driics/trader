package ru.driics.aitrade.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.driics.aitrade.application.risk.KillSwitchSnapshot
import ru.driics.aitrade.application.risk.KillSwitchState
import java.time.Instant

class RiskControllerTest {

    private val killState: KillSwitchState = mockk(relaxed = true)
    private val controller = RiskController(killState)
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    @Test
    fun `GET returns current snapshot`() {
        every { killState.snapshot() } returns KillSwitchSnapshot.disabled()

        mvc.perform(get("/api/trading/kill-switch"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
    }

    @Test
    fun `POST enabled=true calls trip with MANUAL source`() {
        val tripped = KillSwitchSnapshot(true, "paused", Instant.EPOCH, KillSwitchSnapshot.Source.MANUAL)
        every { killState.trip(any(), KillSwitchSnapshot.Source.MANUAL) } returns tripped

        mvc.perform(
            post("/api/trading/kill-switch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true,"reason":"paused"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.reason").value("paused"))
            .andExpect(jsonPath("$.source").value("MANUAL"))

        verify { killState.trip("paused", KillSwitchSnapshot.Source.MANUAL) }
    }

    @Test
    fun `POST enabled=false calls clear`() {
        every { killState.clear() } returns KillSwitchSnapshot.disabled()

        mvc.perform(
            post("/api/trading/kill-switch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":false,"reason":"resume"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))

        verify { killState.clear() }
    }
}

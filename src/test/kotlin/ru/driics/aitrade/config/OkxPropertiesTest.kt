package ru.driics.aitrade.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the OKX WebSocket endpoint URLs. The candle channels MUST resolve to `/ws/v5/business` — they
 * are rejected with error 60018 on `/ws/v5/public`, the bug [businessWsUrl] exists to fix.
 */
class OkxPropertiesTest {

    @Test
    fun `live ws urls resolve to the ws_okx_com host with the right paths`() {
        val props = OkxProperties() // paper = false

        assertThat(props.publicWsUrl()).isEqualTo("wss://ws.okx.com:8443/ws/v5/public")
        assertThat(props.privateWsUrl()).isEqualTo("wss://ws.okx.com:8443/ws/v5/private")
        assertThat(props.businessWsUrl()).isEqualTo("wss://ws.okx.com:8443/ws/v5/business")
    }

    @Test
    fun `paper business ws url uses the wspap host and carries the broker id`() {
        val props = OkxProperties(paper = true, brokerId = "demo-broker")

        assertThat(props.businessWsUrl())
            .isEqualTo("wss://wspap.okx.com:8443/ws/v5/business?brokerId=demo-broker")
    }
}

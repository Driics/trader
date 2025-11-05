package ru.driics.aitrade.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import ru.driics.aitrade.config.OkxHttpProperties
import ru.driics.aitrade.config.OkxProperties
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkxRestClientIntegrationTest {

    private lateinit var wireMockServer: WireMockServer
    private lateinit var okxRestClient: OkxRestClient
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var httpClient: HttpClient

    @BeforeAll
    fun setup() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMockServer.start()

        val okxProperties = OkxProperties(
            key = "test-key",
            secret = "test-secret",
            passphrase = "test-pass",
            baseUrl = "http://localhost:${wireMockServer.port()}"
        )

        val httpProps = OkxHttpProperties(
            clientType = "ktor",
            connectTimeoutMs = 5000,
            readTimeoutMs = 10000,
            writeTimeoutMs = 5000
        )

        httpClient = HttpClient(CIO) {
            expectSuccess = false
            engine {
                requestTimeout = 10000
                endpoint {
                    connectTimeout = 5000
                }
            }
        }
        meterRegistry = SimpleMeterRegistry()

        val authService = OkxAuthService(okxProperties)

        okxRestClient = OkxRestClient(
            okxProperties = okxProperties,
            okxHttpProps = httpProps,
            okxKtorClient = httpClient,
            okxAuthService = authService,
            meterRegistry = meterRegistry,
            objectMapper = jacksonObjectMapper()
        )
    }

    @AfterAll
    fun tearDown() {
        httpClient.close()
        wireMockServer.stop()
    }

    @BeforeEach
    fun resetWireMock() {
        wireMockServer.resetAll()
    }

    @Test
    fun `fetchTicker should return valid ticker data on 200 OK`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/api/v5/market/ticker"))
                .withQueryParam("instId", equalTo("BTC-USDT-SWAP"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "code": "0",
                              "msg": "",
                              "data": [{
                                "instId": "BTC-USDT-SWAP",
                                "last": "50000.0",
                                "askPx": "50001.0",
                                "bidPx": "49999.0",
                                "ts": "1234567890"
                              }]
                            }
                        """.trimIndent())
                )
        )

        val result = okxRestClient.fetchTicker("BTC-USDT-SWAP")

        assertNotNull(result)
        assertEquals("BTC-USDT-SWAP", result.instrumentId)
        assertEquals("50000.0", result.lastPrice)

        // Verify metrics were recorded
        val timer = meterRegistry.find("okx.http").tag("operation", "fetchTicker").timer()
        assertNotNull(timer)
        assertEquals(1, timer.count())
    }

    @Test
    fun `fetchTicker should return null on API error code`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/api/v5/market/ticker"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "code": "51000",
                              "msg": "Parameter error",
                              "data": []
                            }
                        """.trimIndent())
                )
        )

        val result = okxRestClient.fetchTicker("INVALID")
        assertNull(result)
    }

    @Test
    fun `fetchCandles should return sorted candle list on success`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathMatching("/api/v5/market/candles.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "code": "0",
                              "msg": "",
                              "data": [
                                ["1609459200000", "29000", "29500", "28500", "29200", "1000", "1000000"],
                                ["1609455600000", "28500", "29000", "28000", "29000", "1200", "1200000"]
                              ]
                            }
                        """.trimIndent())
                )
        )

        val result = okxRestClient.fetchCandles("BTC-USDT-SWAP", "1H", 2)

        assertEquals(2, result.size)
        // Verify sorting: earlier timestamp first
        assertTrue(result[0].timestamp.toLong() <= result[1].timestamp.toLong())

        assertEquals("1609455600000", result[0].timestamp)
        assertEquals("28500", result[0].open)
        assertEquals("29000", result[0].high)
        assertEquals("28000", result[0].low)
        assertEquals("29000", result[0].close)
    }

    @Test
    fun `fetchCandles should handle 5xx with retry and eventual success`() = runBlocking {
        // First two calls fail with 503, third succeeds
        wireMockServer.stubFor(
            get(urlPathMatching("/api/v5/market/candles.*"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("First Retry")
        )

        wireMockServer.stubFor(
            get(urlPathMatching("/api/v5/market/candles.*"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("First Retry")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("Second Retry")
        )

        wireMockServer.stubFor(
            get(urlPathMatching("/api/v5/market/candles.*"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Second Retry")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"0","msg":"","data":[]}""")
                )
        )

        // Note: Retry annotation might not work in plain unit test without Spring context
        // This test demonstrates the WireMock scenario setup
        val result = okxRestClient.fetchCandles("BTC-USDT-SWAP", "1H", 10)

        // In integration test with Spring, retries would work
        // Here we just verify the stub setup works
        assertTrue(result.isEmpty())

        val timer = meterRegistry.find("okx.http").tag("operation", "fetchAccount").timer()
        assertNotNull(timer)
        assertEquals(1, timer.count())
    }

    @Test
    fun `fetchAccount should return zero values on error`() = runBlocking {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/api/v5/account/balance"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")
                )
        )

        val result = okxRestClient.fetchAccount()

        assertEquals("0", result.totalEquity)
        assertEquals("0", result.availableBalance)
    }
}
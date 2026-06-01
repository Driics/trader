package ru.driics.aitrade.infra.exchange.websocket

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.OkxProperties
import ru.driics.aitrade.infra.exchange.websocket.dto.OkxWsTickerUpdate
import ru.driics.aitrade.service.OkxAuthService
import java.math.BigDecimal
import java.time.Clock

/**
 * Unit specs for the WS message-dispatch routing and reconnect-backoff math — the parts that are
 * verifiable without a live socket. handleTextFrame()/calculateBackoff() are internal seams.
 *
 * Construction does NOT connect (start() is a @PostConstruct we never call here), so the mock
 * HttpClient is never exercised — only the JSON->flow routing path runs.
 */
class OkxWebSocketClientTest {

    private val mapper = jacksonObjectMapper()

    private fun publicClient() =
        OkxPublicWebSocketClient(mockk<HttpClient>(relaxed = true), mapper, OkxProperties())

    private fun businessClient() =
        OkxBusinessWebSocketClient(mockk<HttpClient>(relaxed = true), mapper, OkxProperties())

    private fun privateClient() = OkxPrivateWebSocketClient(
        mockk<HttpClient>(relaxed = true), mapper, OkxProperties(),
        mockk<OkxAuthService>(relaxed = true), Clock.systemUTC(),
    )

    // ---- reconnect backoff ----

    @Test
    fun `calculateBackoff grows exponentially then clamps, with bounded jitter`() {
        val c = publicClient()
        val cfg = WebSocketConfig() // base=1000, max=30000, maxExp=6, jitter=1000

        // base * 2^attempt, plus jitter in [0, jitterMs)
        repeat(100) { assertTrue(c.calculateBackoff(1) in 2_000 until 3_000) }
        repeat(100) { assertTrue(c.calculateBackoff(2) in 4_000 until 5_000) }
        repeat(100) { assertTrue(c.calculateBackoff(3) in 8_000 until 9_000) }
        // exponent capped at maxBackoffExponent AND total clamped at maxReconnectDelay
        repeat(100) {
            val d = c.calculateBackoff(100)
            assertTrue(
                d in cfg.maxReconnectDelayMs until (cfg.maxReconnectDelayMs + cfg.jitterMs),
                "expected clamp to [30000,31000) but was $d",
            )
        }
    }

    // ---- public dispatch ----

    @Test
    fun `ticker message routes to tickerFlow with parsed fields`() = runBlocking {
        val client = publicClient()
        val got = CompletableDeferred<OkxWsTickerUpdate>()
        val subscribed = CompletableDeferred<Unit>()
        // onSubscription fires after the collector is registered on the (replay-0) flow but before
        // any value, so awaiting it makes the subsequent emit deterministically reach the collector.
        val job = launch {
            client.tickerFlow
                .onSubscription { subscribed.complete(Unit) }
                .collect { got.complete(it) }
        }
        subscribed.await()

        client.handleTextFrame(
            """{"arg":{"channel":"tickers","instId":"BTC-USDT-SWAP"},"data":[{"instId":"BTC-USDT-SWAP","last":"50000.1","ts":"1700000000000"}]}""",
        )

        val ticker = withTimeout(2_000) { got.await() }
        assertEquals("BTC-USDT-SWAP", ticker.instId)
        assertEquals("50000.1", ticker.last)
        job.cancel()
    }

    @Test
    fun `pong and garbage text frames are ignored without throwing`() {
        val client = publicClient()
        client.handleTextFrame("pong")
        client.handleTextFrame("not-json{{")
        client.handleTextFrame("""{"event":"subscribe","arg":{"channel":"tickers"}}""")
    }

    // ---- private dispatch (stateful flows replay the last value, so first() is deterministic) ----

    @Test
    fun `position message routes to positionFlow`() = runBlocking {
        val client = privateClient()
        client.handleTextFrame(
            """{"arg":{"channel":"positions","instType":"SWAP"},"data":[{"instId":"BTC-USDT-SWAP","pos":"0.5","posSide":"long","avgPx":"50000","upl":"12.3","lever":"5","ts":"1700000000000"}]}""",
        )
        val pos = withTimeout(2_000) { client.positionFlow.first() }
        assertEquals("BTC-USDT-SWAP", pos.instId)
        assertEquals("0.5", pos.pos)
    }

    @Test
    fun `account message routes to accountFlow`() = runBlocking {
        val client = privateClient()
        client.handleTextFrame(
            """{"arg":{"channel":"account"},"data":[{"totalEq":"1000.5","ts":"1700000000000","details":[{"ccy":"USDT","availBal":"900","cashBal":"1000"}]}]}""",
        )
        val acct = withTimeout(2_000) { client.accountFlow.first() }
        assertEquals(0, BigDecimal("1000.5").compareTo(acct.totalEqOrZero()))
    }

    // ---- business dispatch (candles live on the OKX /ws/v5/business endpoint) ----

    @Test
    fun `candle array message routes to candleFlow`() = runBlocking {
        val client = businessClient()
        val got = CompletableDeferred<OkxBusinessWebSocketClient.CandleEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val job = launch {
            client.candleFlow
                .onSubscription { subscribed.complete(Unit) }
                .collect { got.complete(it) }
        }
        subscribed.await()

        // OKX candle rows are ARRAYS: [ts,o,h,l,c,vol,volCcy,volCcyQuote,confirm]
        client.handleTextFrame(
            """{"arg":{"channel":"candle1m","instId":"BTC-USDT-SWAP"},"data":[["1700000000000","50000","50100","49900","50050","12.3","615000","615000","1"]]}""",
        )

        val event = withTimeout(2_000) { got.await() }
        assertEquals("BTC-USDT-SWAP", event.instId)
        assertEquals("1m", event.period)
        assertEquals("50050", event.candle.c)
        assertTrue(event.candle.isConfirmed())
        job.cancel()
    }
}

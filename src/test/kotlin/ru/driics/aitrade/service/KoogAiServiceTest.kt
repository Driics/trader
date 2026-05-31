package ru.driics.aitrade.service

import ai.koog.prompt.message.Message
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.driics.aitrade.config.TradingProperties
import ru.driics.aitrade.infra.ai.RotatingOpenRouterClient

/**
 * Unit tests the success/failure mapping of [KoogAiService] now that its [RotatingOpenRouterClient] is
 * injected (was built in an init block, which made the service untestable without a live LLM).
 */
class KoogAiServiceTest {

    private val rotatingClient = mockk<RotatingOpenRouterClient>()
    private val meterRegistry = SimpleMeterRegistry()
    private val tradingProperties = TradingProperties(aiModel = "test-model")

    private val stats = RotatingOpenRouterClient.RotationStats(
        totalKeys = 1, currentIndex = 0, currentKey = "k***",
        mode = RotatingOpenRouterClient.KeyMode.SINGLE, callsPerKey = listOf(1), successesPerKey = listOf(1),
    )

    private fun service() = KoogAiService(rotatingClient, meterRegistry, tradingProperties, "system prompt")

    @Test
    fun `a successful LLM response maps to a success result and snapshot`() = runBlocking {
        val response = mockk<Message.Response>()
        every { response.content } returns """{"BTC":{}}"""
        coEvery { rotatingClient.execute(any(), any()) } returns response
        every { rotatingClient.getRotationStats() } returns stats

        val svc = service()
        val result = svc.analyzePrompt("a prompt")

        assertTrue(result.isSuccess)
        assertEquals("""{"BTC":{}}""", result.response)
        assertEquals("test-model", result.model)
        assertEquals("koog-openrouter", result.provider)
        assertEquals(true, svc.getLastAnalysis()?.success)
        assertEquals("""{"BTC":{}}""", svc.getLastAnalysis()?.response)
    }

    @Test
    fun `an LLM failure maps to a failure result and snapshot, never throwing`() = runBlocking {
        coEvery { rotatingClient.execute(any(), any()) } throws RuntimeException("provider exploded")

        val svc = service()
        val result = svc.analyzePrompt("a prompt")

        assertFalse(result.isSuccess)
        assertEquals("provider exploded", result.errorMessage)
        assertEquals("", result.response)
        assertEquals(false, svc.getLastAnalysis()?.success)
        assertEquals("provider exploded", svc.getLastAnalysis()?.error)
    }
}

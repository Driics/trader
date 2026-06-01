package ru.driics.aitrade.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.driics.aitrade.application.ai.PromptTemplateService
import ru.driics.aitrade.application.ai.TemplatedPromptRenderer
import ru.driics.aitrade.domain.model.MarketState
import ru.driics.aitrade.domain.ports.MarketDataPort
import ru.driics.aitrade.domain.ports.PromptOutputPort
import ru.driics.aitrade.domain.services.PromptBuilder
import ru.driics.aitrade.domain.types.asSymbol
import java.time.Clock
import java.util.concurrent.TimeUnit

class BuildPromptUseCase(
    private val market: MarketDataPort,
    private val outputPort: PromptOutputPort,
    private val templateService: PromptTemplateService,
    private val promptRenderer: TemplatedPromptRenderer,
    private val clock: Clock
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    suspend fun execute(
        symbols: List<String>,
        sessionStartMs: Long,
        invocation: Long
    ): PromptResult {
        log.info { "Building prompt for ${symbols.size} symbols (invocation #$invocation)" }

        val state = market.loadMarketState(symbols.map { it.asSymbol() })

        // Use template-based prompt if available, fallback to legacy builder
        val prompt = try {
            buildTemplatePrompt(state, sessionStartMs, invocation)
        } catch (e: Exception) {
            log.warn(e) { "Failed to build template prompt, falling back to legacy builder" }
            PromptBuilder.build(state, sessionStartMs, invocation, clock)
        }

        val written = outputPort.write(prompt)
        if (written) {
            log.debug { "Prompt written to output (${prompt.length} chars, version: ${templateService.version})" }
        } else {
            log.warn { "Failed to write prompt to output" }
        }

        // P2: return the snapshot alongside the prompt so the orchestrator can thread it
        // into execution instead of re-loading market state 2 more times per cycle.
        return PromptResult(prompt = prompt, marketState = state)
    }

    private fun buildTemplatePrompt(
        marketState: ru.driics.aitrade.domain.model.MarketState,
        sessionStartMs: Long,
        invocation: Long
    ): String {
        val minutesSinceStart = TimeUnit.MILLISECONDS.toMinutes(
            clock.instant().toEpochMilli() - sessionStartMs
        )
        return promptRenderer.render(marketState, minutesSinceStart, invocation)
    }
}

/**
 * Result of a prompt build: the rendered [prompt] plus the [marketState] snapshot it was built
 * from. The orchestrator threads [marketState] into the execute stage so a single market load
 * serves prompt-building, risk-context construction, and order execution within one cycle (P2).
 */
data class PromptResult(
    val prompt: String,
    val marketState: MarketState,
)
package ru.driics.aitrade.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import ru.driics.aitrade.application.ai.AiSchemaValidator
import ru.driics.aitrade.domain.backtest.ai.AiDecisionLog
import ru.driics.aitrade.domain.backtest.ai.AiDecisionRecorder
import ru.driics.aitrade.domain.backtest.io.BacktestRunner
import ru.driics.aitrade.domain.backtest.io.JsonlCandleParser
import ru.driics.aitrade.domain.ports.AiAnalysisPort
import ru.driics.aitrade.domain.services.PromptBuilder
import java.io.File
import java.time.Clock

/**
 * Records LIVE AI decisions over a candle file into a replayable decision log (see `RecordedAiStrategy`).
 *
 * THIS CALLS THE LIVE LLM — it costs money and needs the app's AI config, and it loads the full Spring
 * context. It is skipped unless `AI_RECORD_FILE` is set, and it lives in a separate package so the normal
 * fast domain test runs don't pull the context in. Env knobs:
 *   AI_RECORD_FILE     candle JSONL to record over (required)
 *   BACKTEST_SYMBOL    default BTC-USDT-SWAP
 *   AI_RECORD_CADENCE  bars between invocations (default 24 — ~daily on 1H bars; keeps cost/cadence sane)
 *   AI_RECORD_MAX      hard cap on LLM calls (default 100) — bounds cost
 * Writes `<candle>-ai.jsonl` next to the input; replay it with AiReplayHarnessTest.
 *
 * Run (PowerShell):
 *   $env:AI_RECORD_FILE="data/btc.jsonl"
 *   .\gradlew.bat --no-build-cache --rerun-tasks test --tests '*AiRecorderHarnessTest*'
 *
 * NOTE: the prompt is built with the pure [PromptBuilder] (the same data sections the live fallback uses).
 * If the live AI requires the *templated* user-prompt, swap in PromptTemplateService here — mirror
 * BuildPromptUseCase.buildTemplatePrompt. Expect to shake out AI config on the first real run.
 */
@SpringBootTest
class AiRecorderHarnessTest {

    @Autowired
    lateinit var aiPort: AiAnalysisPort

    @Autowired
    lateinit var schemaValidator: AiSchemaValidator

    @Test
    fun `record AI decisions over a candle file when AI_RECORD_FILE is set`() = runBlocking {
        val candlePath = System.getenv("AI_RECORD_FILE")
        assumeTrue(
            !candlePath.isNullOrBlank(),
            "set AI_RECORD_FILE to record AI decisions (CALLS THE LIVE LLM — costs money)",
        )
        val candleFile = File(candlePath!!)
        assumeTrue(candleFile.isFile, "not found: ${candleFile.absolutePath}")

        val symbol = System.getenv("BACKTEST_SYMBOL")?.takeIf { it.isNotBlank() } ?: "BTC-USDT-SWAP"
        val cadence = System.getenv("AI_RECORD_CADENCE")?.toIntOrNull() ?: 24
        val maxCalls = System.getenv("AI_RECORD_MAX")?.toIntOrNull() ?: 100

        val bars = JsonlCandleParser.parse(candleFile.readText())
        val config = BacktestRunner.defaultConfig(symbol)
        val clock = Clock.systemUTC()
        val sessionStart = bars.firstOrNull()?.timestampMs ?: 0L

        val recorder = AiDecisionRecorder(
            symbol = symbol,
            config = config,
            buildPrompt = { state -> PromptBuilder.build(state, sessionStart, state.invocationCount, clock) },
            analyze = { prompt -> aiPort.analyze(prompt) },
            parseDecisions = { json ->
                (schemaValidator.validateAndParse(json) as? AiSchemaValidator.ValidationResult.Valid)?.decisions
            },
            cadenceBars = cadence,
            maxInvocations = maxCalls,
        )

        val decisions = recorder.record(bars) { i, reason ->
            println("skip bar $i (${bars[i].timestampMs}): $reason")
        }
        val out = File(candleFile.absoluteFile.parentFile, candleFile.nameWithoutExtension + "-ai.jsonl")
        out.writeText(AiDecisionLog.serialize(decisions))
        println("Recorded ${decisions.size} AI decisions (cap $maxCalls, cadence $cadence) -> ${out.absolutePath}")

        assertTrue(out.exists())
    }
}

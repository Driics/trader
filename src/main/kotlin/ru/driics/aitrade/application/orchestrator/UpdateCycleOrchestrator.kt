package ru.driics.aitrade.application.orchestrator

import ru.driics.aitrade.application.usecase.AnalyzePromptUseCase
import ru.driics.aitrade.application.usecase.BuildPromptUseCase
import ru.driics.aitrade.application.usecase.ExecuteAiDecisionsUseCase

class UpdateCycleOrchestrator(
    private val build: BuildPromptUseCase,
    private val analyze: AnalyzePromptUseCase,
    private val execute: ExecuteAiDecisionsUseCase,
    private val autoExecute: Boolean,
    private val symbols: List<String>,
    private val sessionStart: () -> Long,
    private val invocation: () -> Long
) {
    suspend fun runOnce(): String {
        val prompt = build.execute(symbols, sessionStart(), invocation())
        val ai = analyze.execute(prompt)
        if (autoExecute && ai.isSuccess) {
            execute.execute(ai.response)
        }
        return if (ai.isSuccess) "Prompt updated and analyzed successfully"
        else "Prompt updated; AI failed: ${ai.errorMessage}"
    }
}
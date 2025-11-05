package ru.driics.aitrade.application.usecase

import ru.driics.aitrade.domain.ports.AiAnalysisPort
import ru.driics.aitrade.model.AiAnalysisResponse

class AnalyzePromptUseCase(
    private val ai: AiAnalysisPort
) {
    suspend fun execute(prompt: String): AiAnalysisResponse = ai.analyze(prompt)
}
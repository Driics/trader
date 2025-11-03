package ru.driics.aitrade.infra.ai

import org.springframework.stereotype.Service
import ru.driics.aitrade.domain.ports.AiAnalysisPort
import ru.driics.aitrade.model.AiAnalysisResponse
import ru.driics.aitrade.model.LastAiAnalysis
import ru.driics.aitrade.service.KoogAiService

@Service
class KoogAiAdapter(
    private val koog: KoogAiService
): AiAnalysisPort {
    override suspend fun analyze(prompt: String): AiAnalysisResponse = koog.analyzePrompt(prompt)

    override val last: LastAiAnalysis?
        get() = koog.getLastAnalysis()
}